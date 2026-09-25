// Package zarpcore is the native side of Zarp for Android: a Cloudflare WARP
// MASQUE client (built on usque) exposed as a local SOCKS5 proxy.
//
// It is compiled with gomobile into an AAR. Everything the Kotlin side
// needs is exported from this package; types use only gomobile-compatible
// signatures.
package zarpcore

import (
	"bytes"
	"log"
	"sync"
)

// Logger receives log lines from the core (usque, quic-go and Zarp code).
type Logger interface {
	Log(line string)
}

var (
	logMu  sync.Mutex
	logger Logger
	logBuf bytes.Buffer
)

type lineWriter struct{}

func (lineWriter) Write(p []byte) (int, error) {
	logMu.Lock()
	defer logMu.Unlock()
	logBuf.Write(p)
	for {
		line, err := logBuf.ReadString('\n')
		if err != nil {
			// incomplete line: keep it for the next Write
			logBuf.Reset()
			logBuf.WriteString(line)
			break
		}
		if logger != nil {
			logger.Log(line[:len(line)-1])
		}
	}
	return len(p), nil
}

// SetLogger routes the standard Go logger to l. Pass nil to drop logs.
func SetLogger(l Logger) {
	logMu.Lock()
	logger = l
	logMu.Unlock()
	log.SetFlags(0)
	log.SetOutput(lineWriter{})
}

func logf(format string, args ...any) {
	log.Printf(format, args...)
}
