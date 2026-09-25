package zarpcore

import (
	"bytes"
	"io"
	"net"
	"reflect"
	"testing"
)

func TestStripPort(t *testing.T) {
	cases := map[string]string{
		"162.159.198.1:0":      "162.159.198.1",
		"[2606:4700:103::1]:0": "2606:4700:103::1",
		"162.159.198.2":        "162.159.198.2",
	}
	for in, want := range cases {
		got, err := stripPort(in)
		if err != nil || got != want {
			t.Errorf("stripPort(%q) = %q, %v; want %q", in, got, err, want)
		}
	}
	if _, err := stripPort("engage.cloudflareclient.com:2408"); err == nil {
		t.Error("hostname must be rejected")
	}
}

func TestParseDNS(t *testing.T) {
	got, err := parseDNS(" 1.1.1.1, 2606:4700:4700::1111 ,")
	if err != nil || len(got) != 2 {
		t.Fatalf("parseDNS: %v %v", got, err)
	}
	if _, err := parseDNS(""); err == nil {
		t.Error("empty list must fail")
	}
	if _, err := parseDNS("dns.google"); err == nil {
		t.Error("hostname must fail")
	}
}

func TestParseDesync(t *testing.T) {
	s, err := parseDesync("disorder:1,midsld")
	if err != nil || s.mode != desyncDisorder || !reflect.DeepEqual(s.pos, []string{"1", "midsld"}) {
		t.Fatalf("parseDesync: %+v %v", s, err)
	}
	for _, bad := range []string{"split", "split:", "fake:1", "split:1,middle"} {
		if _, err := parseDesync(bad); err == nil {
			t.Errorf("parseDesync(%q) must fail", bad)
		}
	}
}

func TestSplitPositions(t *testing.T) {
	host := "consumer-masque.cloudflareclient.com"
	data := append([]byte{0x16, 3, 1, 0, 0, 0, 0, 0, 0, 0}, []byte(host)...)
	data = append(data, 0, 0, 0, 0)
	hostAt := 10
	sldAt := hostAt + len("consumer-masque.")
	got := splitPositions(data, []string{"1", "midsld", "host", "endhost", "sld", "-2", "0", "999"}, host)
	want := []int{1, hostAt, sldAt, sldAt + len("cloudflareclient")/2, hostAt + len(host), len(data) - 2}
	sortInts(want)
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("splitPositions = %v, want %v", got, want)
	}
	// host missing: only numeric positions survive
	if got := splitPositions(data, []string{"midsld", "3"}, "example.org"); !reflect.DeepEqual(got, []int{3}) {
		t.Fatalf("without host = %v", got)
	}
}

func sortInts(a []int) {
	for i := 1; i < len(a); i++ {
		for j := i; j > 0 && a[j] < a[j-1]; j-- {
			a[j], a[j-1] = a[j-1], a[j]
		}
	}
}

func TestDesyncConnSplitKeepsStream(t *testing.T) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	got := make(chan []byte, 1)
	go func() {
		c, err := l.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		b, _ := io.ReadAll(c)
		got <- b
	}()
	raw, err := net.Dial("tcp", l.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	host := "consumer-masque.cloudflareclient.com"
	spec, _ := parseDesync("split:1,midsld")
	c := newDesyncConn(raw.(*net.TCPConn), spec, host)
	hello := append([]byte{0x16, 3, 1, 0, 50, 1, 0, 0, 46}, []byte(host)...)
	rest := bytes.Repeat([]byte("after-handshake "), 4096)
	if n, err := c.Write(hello); err != nil || n != len(hello) {
		t.Fatalf("first write: %d %v", n, err)
	}
	if n, err := c.Write(rest); err != nil || n != len(rest) {
		t.Fatalf("second write: %d %v", n, err)
	}
	_ = raw.(*net.TCPConn).CloseWrite()
	want := append(append([]byte{}, hello...), rest...)
	if b := <-got; !bytes.Equal(b, want) {
		t.Fatalf("stream corrupted: got %d bytes, want %d", len(b), len(want))
	}
}
