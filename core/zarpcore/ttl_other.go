//go:build !unix

package zarpcore

import "errors"

// The core ships only for Android; other platforms build for host-side unit tests.
var errTTLUnsupported = errors.New("TTL control is only implemented for unix")

func getTTL(uintptr, bool) (int, error) { return 0, errTTLUnsupported }

func setTTL(uintptr, bool, int) error { return errTTLUnsupported }
