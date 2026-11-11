//go:build !windows

package rmq

import (
	"syscall"
	"time"
)

// cpuProceso: tiempo de CPU (usuario + sistema) consumido por este proceso.
func cpuProceso() time.Duration {
	var r syscall.Rusage
	if syscall.Getrusage(syscall.RUSAGE_SELF, &r) != nil {
		return 0
	}
	return time.Duration(r.Utime.Nano() + r.Stime.Nano())
}
