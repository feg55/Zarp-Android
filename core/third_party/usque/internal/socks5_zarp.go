// Zarp-Android addition (not part of upstream usque): lets the embedding
// application stop a SOCKS5 server it started.

package internal

// Shutdown stops the TCP and UDP listeners started by Start.
func (s *SOCKS5Server) Shutdown() error {
	return s.server.Shutdown()
}

// AllowUnassociatedUDP accepts UDP datagrams from any local source port.
//
// For UDP ASSOCIATE with a zero address, txthinking/socks5 remembers the
// client's TCP port and drops datagrams from any other port, but clients such
// as hev-socks5-tunnel send from a separate UDP socket. Only use this when the
// server listens on loopback.
func (s *SOCKS5Server) AllowUnassociatedUDP() {
	s.server.LimitUDP = false
}
