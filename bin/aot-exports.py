#!/usr/bin/env python3
"""Add the compiled-arity edge to the runtime unit's manifest.

Only the `--aot` build declares these, so a production module neither exports
them nor carries them -- the same shape `bin/diag-exports.py` uses for
diagnostics, and the same reasoning (doc/decisions/0016 applied to 0013).

They must be exports rather than ordinary symbols because `--gc-sections` runs
BEFORE the compiled arities that call them exist: those are appended after the
link, when the helpers' function indices are finally knowable.
"""
import sys

EXPORTS = ["aot_prologue", "aot_native", "aot_return", "aot_bail", "aot_tick",
           "aot_call", "aot_int_binop", "aot_type_p"]
# Only meaningful when both features are on; harmless to ask for otherwise
# because the linker is told about it only by the diagnostics build.
DIAG_ONLY = ["stat_sync_drift"]
if "stat_stale_push" in open(sys.argv[1]).read():
    EXPORTS = EXPORTS + DIAG_ONLY

path = sys.argv[1]
s = open(path).read()
assert s.rstrip().endswith("}"), "unit manifest should end with a map"
add = " ".join('"%s"' % e for e in EXPORTS)
if ":exports [" in s:
    s = s.replace(":exports [", ":exports [" + add + " ", 1)
else:
    s = s.rstrip()[:-1] + " :exports [" + add + "]}\n"
open(path, "w").write(s)
