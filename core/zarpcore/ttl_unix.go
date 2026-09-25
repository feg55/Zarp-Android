//go:build unix

package zarpcore

import "syscall"

func getTTL(fd uintptr, v6 bool) (int, error) {
	if v6 {
		return syscall.GetsockoptInt(int(fd), syscall.IPPROTO_IPV6, syscall.IPV6_UNICAST_HOPS)
	}
	return syscall.GetsockoptInt(int(fd), syscall.IPPROTO_IP, syscall.IP_TTL)
}

func setTTL(fd uintptr, v6 bool, ttl int) error {
	if v6 {
		return syscall.SetsockoptInt(int(fd), syscall.IPPROTO_IPV6, syscall.IPV6_UNICAST_HOPS, ttl)
	}
	return syscall.SetsockoptInt(int(fd), syscall.IPPROTO_IP, syscall.IP_TTL, ttl)
}
