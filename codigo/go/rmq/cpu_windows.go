package rmq

import (
	"syscall"
	"time"
)

// cpuProceso: tiempo de CPU (usuario + kernel) consumido por este proceso.
func cpuProceso() time.Duration {
	var c, e, k, u syscall.Filetime
	h, _ := syscall.GetCurrentProcess()
	if syscall.GetProcessTimes(h, &c, &e, &k, &u) != nil {
		return 0
	}
	t := func(f syscall.Filetime) int64 { return int64(f.HighDateTime)<<32 | int64(f.LowDateTime) }
	return time.Duration((t(k) + t(u)) * 100) // unidades de 100 ns
}
