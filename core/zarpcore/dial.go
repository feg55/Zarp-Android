package zarpcore

// The HTTP/3 and HTTP/2 dialers follow usque's api.ConnectTunnel
// (github.com/Diniboy1123/usque, MIT), changed so that the UDP socket of the
// QUIC connection comes from the SocketFactory: the Kotlin side sends the Zarp
// strategy's fake packets from that exact socket before the real QUIC Initial.

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"strings"
	"sync"
	"syscall"
	"time"

	connectip "github.com/Diniboy1123/connect-ip-go"
	"github.com/quic-go/quic-go"
	"github.com/quic-go/quic-go/http3"
	"github.com/yosida95/uritemplate/v3"
	"golang.org/x/net/http2"
)

// session is one established CONNECT-IP tunnel.
type session struct {
	ipConn    *connectip.Conn
	closers   []func()
	failed    chan struct{}
	failErr   error
	failOnce  sync.Once
	closeOnce sync.Once
}

func newSession(ipConn *connectip.Conn, closers ...func()) *session {
	return &session{ipConn: ipConn, closers: closers, failed: make(chan struct{})}
}

// fail marks the session as lost; the first error wins.
func (s *session) fail(err error) {
	s.failOnce.Do(func() {
		s.failErr = err
		close(s.failed)
	})
}

func (s *session) close() {
	s.closeOnce.Do(func() {
		_ = s.ipConn.Close()
		for i := len(s.closers) - 1; i >= 0; i-- {
			s.closers[i]()
		}
	})
}

var errAccessDenied = errors.New("WARP rejected the device key (tls: access denied); re-register the account")

func wrapDialErr(err error) error {
	if err != nil && strings.Contains(err.Error(), "tls: access denied") {
		return errAccessDenied
	}
	return err
}

// udpConnFromFd adopts a UDP socket created on the Kotlin side.
func udpConnFromFd(fd int) (*net.UDPConn, error) {
	f := os.NewFile(uintptr(fd), "zarp-udp")
	if f == nil {
		return nil, fmt.Errorf("invalid socket fd %d", fd)
	}
	pc, err := net.FilePacketConn(f)
	_ = f.Close() // FilePacketConn made its own dup
	if err != nil {
		return nil, err
	}
	uc, ok := pc.(*net.UDPConn)
	if !ok {
		_ = pc.Close()
		return nil, fmt.Errorf("fd %d is not a UDP socket", fd)
	}
	return uc, nil
}

func (t *Tunnel) dialH3(ctx context.Context) (*session, error) {
	ep := t.udpEndpoint
	start := time.Now()
	// create UDP socket -> strategy sends fakes -> socket comes back to us
	fd, err := t.sockets.OpenUDP(ep.IP.String(), ep.Port)
	if err != nil {
		return nil, fmt.Errorf("strategy socket: %w", err)
	}
	logf("dial %s: socket ready in %d ms", ep, time.Since(start).Milliseconds())
	udpConn, err := udpConnFromFd(fd)
	if err != nil {
		return nil, err
	}

	// the real QUIC Initial leaves through the same socket (same 5-tuple as the fakes)
	// without ConnectionIDLength set, backend occasionally throws PROTOCOL_VIOLATION
	qtr := &quic.Transport{Conn: udpConn, ConnectionIDLength: 20}
	conn, err := qtr.Dial(ctx, ep, t.tlsConfig, t.quicConfig())
	if err != nil {
		_ = qtr.Close()
		_ = udpConn.Close()
		logf("dial %s: QUIC handshake failed after %d ms: %v", ep, time.Since(start).Milliseconds(), err)
		return nil, wrapDialErr(err)
	}
	logf("dial %s: QUIC handshake done in %d ms", ep, time.Since(start).Milliseconds())

	tr := &http3.Transport{
		EnableDatagrams: true,
		AdditionalSettings: map[uint64]uint64{
			// SETTINGS_H3_DATAGRAM_00, still sent by the official client
			0x276: 1,
		},
		DisableCompression: true,
	}
	hconn := tr.NewClientConn(conn)
	template := uritemplate.MustNew(connectURI)
	headers := http.Header{"User-Agent": []string{""}}
	// connect-ip does not stop reading the response at the deadline; closing the
	// QUIC connection does. Without this a blocked tunnel waits for the idle timeout.
	stop := context.AfterFunc(ctx, func() { _ = conn.CloseWithError(0, "connect timeout") })
	ipConn, rsp, err := connectip.Dial(ctx, hconn, template, "cf-connect-ip", headers, true)
	stop()
	if err != nil && ctx.Err() != nil {
		err = fmt.Errorf("%w (%v)", ctx.Err(), err)
	}
	closeAll := func() {
		_ = tr.Close()
		_ = conn.CloseWithError(0, "")
		_ = qtr.Close()
		_ = udpConn.Close()
	}
	if err != nil {
		closeAll()
		return nil, wrapDialErr(fmt.Errorf("connect-ip: %w", err))
	}
	if rsp.StatusCode != http.StatusOK {
		_ = ipConn.Close()
		closeAll()
		return nil, fmt.Errorf("connect-ip: %s", rsp.Status)
	}
	return newSession(ipConn, closeAll), nil
}

func (t *Tunnel) dialH2(ctx context.Context) (*session, error) {
	ep := t.tcpEndpoint
	tlsConfig := t.tlsConfig.Clone()
	tlsConfig.NextProtos = []string{"h2"}

	dialer := &net.Dialer{
		Control: func(_, _ string, c syscall.RawConn) error {
			var perr error
			err := c.Control(func(fd uintptr) {
				if !t.sockets.Protect(int(fd)) {
					perr = errors.New("VpnService.protect failed")
				}
			})
			if err != nil {
				return err
			}
			return perr
		},
	}
	desync := t.tcpDesync
	transport := &http2.Transport{
		DialTLSContext: func(ctx context.Context, network, _ string, _ *tls.Config) (net.Conn, error) {
			raw, err := dialer.DialContext(ctx, "tcp", ep.String())
			if err != nil {
				return nil, err
			}
			var conn net.Conn = raw
			if desync != nil {
				conn = newDesyncConn(raw.(*net.TCPConn), desync, tlsConfig.ServerName)
			}
			tlsConn := tls.Client(conn, tlsConfig)
			if err := tlsConn.HandshakeContext(ctx); err != nil {
				_ = raw.Close()
				return nil, err
			}
			return tlsConn, nil
		},
	}
	client := &http.Client{Transport: transport}
	headers := http.Header{"User-Agent": []string{""}}
	headers.Set("cf-connect-proto", "cf-connect-ip")
	headers.Set("pq-enabled", "false")
	template := uritemplate.MustNew(connectURI)
	// Over HTTP/2 the CONNECT-IP stream lives as long as the request context, so it
	// must be the tunnel's context; ctx (the connect deadline) only aborts the dial.
	reqCtx, reqCancel := context.WithCancel(t.ctx)
	stop := context.AfterFunc(ctx, reqCancel)
	ipConn, rsp, err := connectip.DialH2(reqCtx, client, template, headers)
	stopped := stop()
	closeAll := func() {
		reqCancel()
		transport.CloseIdleConnections()
	}
	if err != nil {
		closeAll()
		if !stopped || ctx.Err() != nil {
			err = fmt.Errorf("%w (%v)", context.DeadlineExceeded, err)
		}
		return nil, wrapDialErr(fmt.Errorf("connect-ip over HTTP/2: %w", err))
	}
	if rsp.StatusCode != http.StatusOK {
		_ = ipConn.Close()
		closeAll()
		return nil, fmt.Errorf("connect-ip over HTTP/2: %s", rsp.Status)
	}
	return newSession(ipConn, closeAll), nil
}
