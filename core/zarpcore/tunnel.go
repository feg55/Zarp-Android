package zarpcore

import (
	"context"
	"crypto/ecdsa"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	connectip "github.com/Diniboy1123/connect-ip-go"
	"github.com/Diniboy1123/usque/api"
	"github.com/Diniboy1123/usque/config"
	"github.com/Diniboy1123/usque/pub"
	"github.com/quic-go/quic-go"
	"golang.zx2c4.com/wireguard/tun"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

const (
	connectURI = pub.ConnectURI
	// MTU inside the MASQUE tunnel; usque supports only 1280.
	tunnelMTU = 1280
	// CONNECT-IP context ID 0 is a one-byte varint written in front of each packet.
	contextIDHeadroom = 1
)

// SocketFactory is implemented on the Kotlin side.
type SocketFactory interface {
	// OpenUDP creates a UDP socket excluded from the VPN, runs the Zarp strategy
	// on it (fake packets to host:port) and returns its file descriptor.
	// Ownership of the descriptor passes to the core.
	OpenUDP(host string, port int) (int, error)
	// Protect excludes a socket created by the core from the VPN.
	Protect(fd int) bool
}

// StatusListener receives tunnel state changes after the first connection.
type StatusListener interface {
	OnStatus(connected bool, message string)
}

// TunnelOptions configures a Tunnel. Create it with NewTunnelOptions.
type TunnelOptions struct {
	// Endpoint is "ip:port" of the WARP MASQUE server. Empty = registered endpoint, port 443.
	Endpoint string
	// HTTP2 selects MASQUE over HTTP/2 (TLS/TCP) instead of HTTP/3 (QUIC/UDP).
	HTTP2 bool
	// TCPDesync is an HTTP/2-only ClientHello desync, e.g. "split:1,midsld" or "disorder:1,midsld".
	TCPDesync string
	// SocksPort for the local SOCKS5 proxy on 127.0.0.1; 0 picks a free port.
	SocksPort int
	// ConnectTimeoutMs bounds the whole first connection (QUIC/TLS handshake + CONNECT-IP).
	ConnectTimeoutMs int
	// KeepaliveSec is the QUIC keep-alive period.
	KeepaliveSec int
	// IPv6 enables IPv6 inside the tunnel.
	IPv6 bool
	// DNS is a comma-separated list of DNS servers used inside the tunnel.
	DNS string
	// Reconnect keeps the tunnel alive after connection loss (VPN mode).
	Reconnect bool
}

// NewTunnelOptions returns options with defaults.
func NewTunnelOptions() *TunnelOptions {
	return &TunnelOptions{
		ConnectTimeoutMs: 15000,
		KeepaliveSec:     30,
		DNS:              "1.1.1.1,1.0.0.1,2606:4700:4700::1111",
	}
}

// Tunnel is one WARP MASQUE connection exposed as a local SOCKS5 proxy.
type Tunnel struct {
	opts        TunnelOptions
	sockets     SocketFactory
	listener    StatusListener
	tlsConfig   *tls.Config
	udpEndpoint *net.UDPAddr
	tcpEndpoint *net.TCPAddr
	tcpDesync   *desyncSpec

	ctx    context.Context
	cancel context.CancelFunc

	mu        sync.Mutex
	started   bool
	closed    bool
	dev       tun.Device
	devIO     api.TunnelDevice
	socks     *pub.SOCKS5Server
	socksOn   bool
	socksPort int
	connectMs int64
	cur       atomic.Pointer[session]
	connected atomic.Bool
	wg        sync.WaitGroup
}

// NewTunnel prepares a tunnel from a usque config written by Register.
func NewTunnel(configPath string, opts *TunnelOptions, sockets SocketFactory, listener StatusListener) (*Tunnel, error) {
	if opts == nil {
		opts = NewTunnelOptions()
	}
	if sockets == nil {
		return nil, errors.New("socket factory is required")
	}
	cfg, err := readConfig(configPath)
	if err != nil {
		return nil, err
	}
	tlsConfig, err := tlsConfigFor(cfg)
	if err != nil {
		return nil, err
	}
	t := &Tunnel{opts: *opts, sockets: sockets, listener: listener, tlsConfig: tlsConfig}

	host, port := cfg.EndpointV4, 443
	if opts.HTTP2 {
		host = cfg.EndpointH2V4
		if host == "" {
			host = config.DefaultEndpointH2V4
		}
	}
	if opts.Endpoint != "" {
		h, p, err := net.SplitHostPort(opts.Endpoint)
		if err != nil {
			return nil, fmt.Errorf("endpoint %q: %w", opts.Endpoint, err)
		}
		host = h
		if _, err := fmt.Sscan(p, &port); err != nil {
			return nil, fmt.Errorf("endpoint %q: bad port", opts.Endpoint)
		}
	}
	ip := net.ParseIP(host)
	if ip == nil {
		return nil, fmt.Errorf("endpoint %q is not an IP address", host)
	}
	if opts.HTTP2 {
		t.tcpEndpoint = &net.TCPAddr{IP: ip, Port: port}
		if opts.TCPDesync != "" {
			spec, err := parseDesync(opts.TCPDesync)
			if err != nil {
				return nil, err
			}
			t.tcpDesync = spec
		}
	} else {
		t.udpEndpoint = &net.UDPAddr{IP: ip, Port: port}
	}

	local := []netip.Addr{}
	if a, err := netip.ParseAddr(cfg.IPv4); err == nil {
		local = append(local, a)
	}
	if opts.IPv6 {
		if a, err := netip.ParseAddr(cfg.IPv6); err == nil {
			local = append(local, a)
		}
	}
	if len(local) == 0 {
		return nil, errors.New("config has no tunnel address")
	}
	dns, err := parseDNS(opts.DNS)
	if err != nil {
		return nil, err
	}
	dev, tnet, err := netstack.CreateNetTUN(local, dns, tunnelMTU)
	if err != nil {
		return nil, fmt.Errorf("netstack: %w", err)
	}
	t.dev = dev
	t.devIO = api.NewNetstackAdapter(dev)
	t.ctx, t.cancel = context.WithCancel(context.Background())

	resolver := &pub.TunnelDNSResolver{TunNet: tnet, DNSAddrs: dns, Timeout: 3 * time.Second}
	t.socksPort = opts.SocksPort
	if t.socksPort == 0 {
		if t.socksPort, err = freePort(); err != nil {
			_ = dev.Close()
			return nil, err
		}
	}
	t.socks, err = pub.NewSOCKS5Server(pub.SOCKS5Config{
		Addr:       net.JoinHostPort("127.0.0.1", fmt.Sprint(t.socksPort)),
		Resolver:   resolver,
		TunNet:     tnet,
		UDPTimeout: 60 * time.Second,
		Logger:     log.New(io.Discard, "", 0),
	})
	if err != nil {
		_ = dev.Close()
		return nil, err
	}
	// loopback only; hev-socks5-tunnel sends UDP from another port than its TCP control connection
	t.socks.AllowUnassociatedUDP()
	return t, nil
}

func tlsConfigFor(cfg *config.Config) (*tls.Config, error) {
	der, err := base64.StdEncoding.DecodeString(cfg.PrivateKey)
	if err != nil {
		return nil, fmt.Errorf("private key: %w", err)
	}
	privKey, err := x509.ParseECPrivateKey(der)
	if err != nil {
		return nil, fmt.Errorf("private key: %w", err)
	}
	block, _ := pem.Decode([]byte(cfg.EndpointPubKey))
	if block == nil {
		return nil, errors.New("endpoint public key: bad PEM")
	}
	pk, err := x509.ParsePKIXPublicKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("endpoint public key: %w", err)
	}
	peer, ok := pk.(*ecdsa.PublicKey)
	if !ok {
		return nil, errors.New("endpoint public key is not ECDSA")
	}
	cert, err := pub.GenerateCert(privKey, &privKey.PublicKey)
	if err != nil {
		return nil, err
	}
	return api.PrepareTlsConfig(privKey, peer, cert, pub.ConnectSNI, false)
}

func parseDNS(s string) ([]netip.Addr, error) {
	var out []netip.Addr
	for _, part := range strings.Split(s, ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		a, err := netip.ParseAddr(part)
		if err != nil {
			return nil, fmt.Errorf("DNS server %q: %w", part, err)
		}
		out = append(out, a)
	}
	if len(out) == 0 {
		return nil, errors.New("no DNS servers")
	}
	return out, nil
}

func freePort() (int, error) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	defer func() { _ = l.Close() }()
	return l.Addr().(*net.TCPAddr).Port, nil
}

func (t *Tunnel) quicConfig() *quic.Config {
	c := pub.DefaultQuicConfig(time.Duration(t.opts.KeepaliveSec)*time.Second, 0)
	// quic-go gives up on the handshake after 5 s by default; Zarp's test timeout is longer
	if d := time.Duration(t.opts.ConnectTimeoutMs) * time.Millisecond; d > 0 {
		c.HandshakeIdleTimeout = d
	}
	return c
}

func (t *Tunnel) dial(ctx context.Context) (*session, error) {
	if t.opts.HTTP2 {
		return t.dialH2(ctx)
	}
	return t.dialH3(ctx)
}

// EndpointString is the WARP endpoint this tunnel connects to.
func (t *Tunnel) EndpointString() string {
	if t.opts.HTTP2 {
		return t.tcpEndpoint.String()
	}
	return t.udpEndpoint.String()
}

// Connect establishes the MASQUE tunnel (blocking, bounded by ConnectTimeoutMs)
// and starts the SOCKS5 proxy. The returned error describes why the connection failed.
func (t *Tunnel) Connect() error {
	t.mu.Lock()
	if t.started || t.closed {
		t.mu.Unlock()
		return errors.New("tunnel already used")
	}
	t.started = true
	t.mu.Unlock()

	start := time.Now()
	ctx, cancel := context.WithTimeout(t.ctx, time.Duration(t.opts.ConnectTimeoutMs)*time.Millisecond)
	s, err := t.dial(ctx)
	cancel()
	if err != nil {
		logf("MASQUE %s to %s failed after %d ms: %v", t.transportName(), t.EndpointString(), time.Since(start).Milliseconds(), err)
		if errors.Is(err, context.DeadlineExceeded) || isTimeout(err) {
			return fmt.Errorf("timeout after %d ms: %w", t.opts.ConnectTimeoutMs, err)
		}
		return err
	}
	t.connectMs = time.Since(start).Milliseconds()
	logf("MASQUE %s connected to %s in %d ms", t.transportName(), t.EndpointString(), t.connectMs)

	t.mu.Lock()
	if t.closed { // Close raced with the handshake
		t.mu.Unlock()
		s.close()
		return errors.New("tunnel closed")
	}
	t.socksOn = true
	t.mu.Unlock()
	t.cur.Store(s)
	t.connected.Store(true)
	t.wg.Add(3)
	go t.readDevice()
	go t.maintain(s)
	go func() {
		defer t.wg.Done()
		if err := t.socks.Start(); err != nil && t.ctx.Err() == nil {
			logf("SOCKS5 proxy stopped: %v", err)
		}
	}()
	if err := waitListening(t.socksPort, 2*time.Second); err != nil {
		t.Close()
		return fmt.Errorf("SOCKS5 proxy: %w", err)
	}
	return nil
}

func isTimeout(err error) bool {
	var ne net.Error
	if errors.As(err, &ne) && ne.Timeout() {
		return true
	}
	var idle *quic.IdleTimeoutError
	if errors.As(err, &idle) {
		return true
	}
	var hs *quic.HandshakeTimeoutError
	return errors.As(err, &hs)
}

func waitListening(port int, limit time.Duration) error {
	deadline := time.Now().Add(limit)
	addr := net.JoinHostPort("127.0.0.1", fmt.Sprint(port))
	for {
		c, err := net.DialTimeout("tcp", addr, 200*time.Millisecond)
		if err == nil {
			_ = c.Close()
			return nil
		}
		if time.Now().After(deadline) {
			return err
		}
		time.Sleep(20 * time.Millisecond)
	}
}

func (t *Tunnel) transportName() string {
	if t.opts.HTTP2 {
		return "HTTP/2"
	}
	return "HTTP/3"
}

// readDevice forwards packets from the netstack (SOCKS5 side) into the current session.
func (t *Tunnel) readDevice() {
	defer t.wg.Done()
	buf := make([]byte, tunnelMTU+contextIDHeadroom)
	for {
		n, err := t.devIO.ReadPacket(buf[contextIDHeadroom:])
		if err != nil {
			return // device closed
		}
		s := t.cur.Load()
		if s == nil {
			continue // reconnecting: drop, TCP inside will retransmit
		}
		icmp, err := s.ipConn.WritePacketBuffer(buf, contextIDHeadroom, n)
		if err != nil {
			if errors.As(err, new(*connectip.CloseError)) {
				s.fail(err)
			}
			continue
		}
		if len(icmp) > 0 {
			_ = t.devIO.WritePacket(icmp)
		}
	}
}

// pumpIn forwards packets from the session into the netstack until the session fails.
func (t *Tunnel) pumpIn(s *session) {
	for {
		pkt, err := s.ipConn.ReadPacketZeroCopy(true)
		if err != nil {
			if t.opts.HTTP2 || errors.As(err, new(*connectip.CloseError)) {
				s.fail(err)
				return
			}
			continue
		}
		if err := t.devIO.WritePacket(pkt); err != nil {
			s.fail(err)
			return
		}
	}
}

// maintain keeps the tunnel up: every reconnect opens a new socket through the
// SocketFactory, so the Zarp strategy runs again before each QUIC handshake.
func (t *Tunnel) maintain(s *session) {
	defer t.wg.Done()
	delay := time.Second
	for {
		go t.pumpIn(s)
		select {
		case <-t.ctx.Done():
			t.cur.Store(nil)
			s.close()
			return
		case <-s.failed:
		}
		t.cur.Store(nil)
		t.connected.Store(false)
		s.close()
		if t.ctx.Err() != nil {
			return
		}
		logf("MASQUE connection lost: %v", s.failErr)
		t.notify(false, fmt.Sprint(s.failErr))
		if !t.opts.Reconnect {
			return
		}
		for {
			select {
			case <-t.ctx.Done():
				return
			case <-time.After(delay):
			}
			ctx, cancel := context.WithTimeout(t.ctx, time.Duration(t.opts.ConnectTimeoutMs)*time.Millisecond)
			next, err := t.dial(ctx)
			cancel()
			if err == nil {
				s = next
				delay = time.Second
				break
			}
			if t.ctx.Err() != nil {
				return
			}
			logf("MASQUE reconnect failed: %v", err)
			if delay < 10*time.Second {
				delay *= 2
			}
		}
		t.cur.Store(s)
		t.connected.Store(true)
		logf("MASQUE reconnected to %s", t.EndpointString())
		t.notify(true, "reconnected")
	}
}

func (t *Tunnel) notify(connected bool, msg string) {
	if t.listener != nil {
		t.listener.OnStatus(connected, msg)
	}
}

// ConnectMillis is how long the first connection took.
func (t *Tunnel) ConnectMillis() int64 { return t.connectMs }

// SocksPort is the local SOCKS5 port on 127.0.0.1.
func (t *Tunnel) SocksPort() int { return t.socksPort }

// Connected reports whether a MASQUE session is currently up.
func (t *Tunnel) Connected() bool { return t.connected.Load() }

// Close stops the proxy and the MASQUE connection. Safe to call more than once.
func (t *Tunnel) Close() {
	t.mu.Lock()
	if t.closed {
		t.mu.Unlock()
		return
	}
	t.closed = true
	socksOn := t.socksOn
	t.mu.Unlock()

	t.cancel()
	t.connected.Store(false)
	if socksOn {
		_ = t.socks.Shutdown()
	}
	_ = t.dev.Close()
	if s := t.cur.Swap(nil); s != nil {
		s.close()
	}
	done := make(chan struct{})
	go func() { t.wg.Wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		logf("tunnel close: workers still running after 3 s")
	}
}
