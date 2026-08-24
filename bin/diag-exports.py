#!/usr/bin/env python3
"""Add the diagnostic edge to the runtime unit's manifest.

Only the `--diagnostics` build declares these, which is what keeps a production
module free of them without `flint` needing a flag of its own
(doc/decisions/0016).
"""
import sys

EXPORTS = [
    "stat_bytes_allocated", "stat_collections", "stat_peak_live", "stat_heap_used",
    "collect_now", "set_gc_stress", "set_gc_stress_window", "stat_allocs",
    "set_gc_upgrade_window",
    "set_gc_verify_remset", "stat_remset_violations",
    "stat_remset_bad", "stat_remset_end_violations", "stat_remset_cover",
    "set_gc_remset_watch", "stat_dead_half", "stat_limbo",
    "set_gc_watch_end", "stat_end_bump", "stat_origin", "stat_origin_seq", "stat_stale_set", "stat_stale_root", "stat_stale_shadow", "stat_stale_push", "stat_region", "stat_static", "stat_native_trace", "stat_restore_stale", "set_gc_origin_window", "stat_native_slot", "stat_native_name",
]

path = sys.argv[1]
s = open(path).read().rstrip()
cut = s.rfind("}")
if cut < 0:
    sys.exit(f"{path}: not a unit manifest")
s = s[:cut] + " :exports [" + " ".join(f'"{e}"' for e in EXPORTS) + "]}\n"
open(path, "w").write(s)
