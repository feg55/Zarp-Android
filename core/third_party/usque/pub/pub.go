// Package pub is a Zarp-Android addition (not part of upstream usque).
//
// It re-exports the helpers from usque/internal that an embedding
// application needs, without changing any upstream code.
package pub

import (
	"github.com/Diniboy1123/usque/internal"
)

const (
	ConnectSNI = internal.ConnectSNI
	ConnectURI = internal.ConnectURI
)

type (
	SOCKS5Config      = internal.SOCKS5Config
	SOCKS5Server      = internal.SOCKS5Server
	TunnelDNSResolver = internal.TunnelDNSResolver
)

var (
	GenerateCert      = internal.GenerateCert
	GenerateEcKeyPair = internal.GenerateEcKeyPair
	DefaultQuicConfig = internal.DefaultQuicConfig
	NewSOCKS5Server   = internal.NewSOCKS5Server
)
