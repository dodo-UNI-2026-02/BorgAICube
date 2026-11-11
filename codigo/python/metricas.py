"""Medición de CPU y memoria del propio proceso usando solo la biblioteca estándar
(sin psutil ni herramientas externas)."""
import os
import sys
import time


class Medidor:
    def __init__(self):
        self._cpu = time.process_time()
        self._t = time.perf_counter()
        self._nucleos = os.cpu_count() or 1

    def muestra(self):
        """Devuelve (cpu_pct, mem_mb). cpu_pct es relativo al total de núcleos."""
        cpu, t = time.process_time(), time.perf_counter()
        dt = t - self._t
        pct = (cpu - self._cpu) / (dt * self._nucleos) * 100 if dt > 0 else 0.0
        self._cpu, self._t = cpu, t
        return pct, memoria_mb()


def memoria_mb():
    if sys.platform.startswith("linux"):
        try:
            with open("/proc/self/status") as f:
                for linea in f:
                    if linea.startswith("VmRSS:"):
                        return int(linea.split()[1]) / 1024
        except OSError:
            return 0.0
    if sys.platform == "win32":
        import ctypes
        from ctypes import wintypes

        class PMC(ctypes.Structure):
            _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD),
                        ("PeakWorkingSetSize", ctypes.c_size_t), ("WorkingSetSize", ctypes.c_size_t),
                        ("QuotaPeakPagedPoolUsage", ctypes.c_size_t), ("QuotaPagedPoolUsage", ctypes.c_size_t),
                        ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t), ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
                        ("PagefileUsage", ctypes.c_size_t), ("PeakPagefileUsage", ctypes.c_size_t)]
        pmc = PMC()
        pmc.cb = ctypes.sizeof(PMC)
        # Tipos explícitos: sin ellos ctypes pasa el HANDLE como entero de 32 bits
        # y en Windows de 64 bits la llamada falla (devolvía siempre 0 MB).
        kernel32, psapi = ctypes.WinDLL("kernel32"), ctypes.WinDLL("psapi")
        kernel32.GetCurrentProcess.restype = wintypes.HANDLE
        psapi.GetProcessMemoryInfo.argtypes = [wintypes.HANDLE, ctypes.POINTER(PMC), wintypes.DWORD]
        psapi.GetProcessMemoryInfo.restype = wintypes.BOOL
        proceso = kernel32.GetCurrentProcess()
        if psapi.GetProcessMemoryInfo(proceso, ctypes.byref(pmc), pmc.cb):
            return pmc.WorkingSetSize / (1024 * 1024)
        return 0.0
    try:
        import resource
        r = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
        return r / (1024 * 1024) if sys.platform == "darwin" else r / 1024
    except Exception:
        return 0.0
