package app

import (
	"os"
	"runtime/debug"
	"strconv"
	"strings"
)

// ApplyCgroupMemoryLimit sets the Go soft memory limit to 85% of the
// systemd/cgroup v2 memory.high (or memory.max) when GOMEMLIMIT isn't set,
// so the GC works harder before the kernel starts throttling or OOM-killing
// on a small VM. Returns the limit applied (0 = none).
func ApplyCgroupMemoryLimit() int64 {
	if os.Getenv("GOMEMLIMIT") != "" {
		return 0
	}
	dir := "/sys/fs/cgroup"
	if b, err := os.ReadFile("/proc/self/cgroup"); err == nil {
		for _, line := range strings.Split(string(b), "\n") {
			if p, ok := strings.CutPrefix(line, "0::"); ok && strings.HasPrefix(p, "/") && !strings.Contains(p, "..") {
				dir += strings.TrimRight(p, "/")
				break
			}
		}
	}
	for _, f := range []string{dir + "/memory.high", dir + "/memory.max"} {
		b, err := os.ReadFile(f)
		if err != nil {
			continue
		}
		v, err := strconv.ParseInt(strings.TrimSpace(string(b)), 10, 64)
		if err != nil || v <= 0 { // "max" = unlimited
			continue
		}
		limit := v * 85 / 100
		if limit < 64<<20 {
			return 0
		}
		debug.SetMemoryLimit(limit)
		return limit
	}
	return 0
}
