"""Every materialisation of a rope must say why it is one.

`doc/decisions/0011` buys two things with a tree: concatenation that is not
quadratic, and SHARING. Flattening spends the second to get the first back, and
it is always the easy move -- the platform's own string functions want
contiguous bytes, so "flatten first" is what a tired hand writes.

It had been written a lot. `subs` flattened on the ASCII path while the
non-ASCII path carefully descended; `str-bytes` flattened to walk bytes it could
have walked in the tree; `nth` flattened for ASCII and descended otherwise. None
of it was wrong, all of it was slower than the type it was implementing, and
none of it was visible because a flatten produces a correct answer.

So: every call site is listed here with a justification, and a site that is not
listed fails the build. The list is per runtime and the runtimes must AGREE --
a justification that holds for Rust and not for the JVM is a port that has
drifted.
"""
import re, sys, os

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# site -> why it must materialise. Keep the reason concrete.
JUSTIFIED = {
    "hands bytes to the host across the ABI": [
        ("runtime/src/abi.rs", "string_arg"),
        ("runtime/src/native.rs", "string_arg"),
    ],
    "a value crossing a port becomes wire bytes; the tree is internal": [
        ("runtime/src/conc.rs", "string_arg"),
        ("runtimes/jvm/src/com/flint/rt/Conc.java", "Str.flatten"),
        ("runtimes/clr/src/rt/Conc.cs", "Str.Flatten"),
    ],
    "hashing caches the flat form, so a rope used as a map key pays once": [
        ("runtime/src/eq.rs", "flatten"),
        ("runtimes/jvm/src/com/flint/rt/Str.java", "flatten"),
        ("runtimes/clr/src/rt/Str.cs", "Flatten"),
    ],
    "a search needs contiguous bytes, and caching pays off when the same "
    "string is searched again": [
        ("runtime/src/coll.rs", "string_arg"),
    ],
    "byte strings: the same three reasons, one type down": [
        ("runtimes/jvm/src/com/flint/rt/Bytes.java", "flatten"),
        ("runtimes/clr/src/rt/Bytes.cs", "Flatten"),
    ],
}

ALLOWED = set()
for why, sites in JUSTIFIED.items():
    for f, name in sites:
        ALLOWED.add((f, name))

CALL = re.compile(r'\b(string_arg|flatten|Flatten|Str\.flatten|Str\.Flatten)\s*\(')
SCAN = [
    "runtime/src/abi.rs", "runtime/src/coll.rs", "runtime/src/conc.rs",
    "runtime/src/eq.rs", "runtime/src/native.rs", "runtime/src/rt.rs",
    "runtime/src/rope.rs", "runtime/src/bytes.rs", "runtime/src/strs.rs",
    "runtimes/jvm/src/com/flint/rt/Str.java", "runtimes/jvm/src/com/flint/rt/Bytes.java",
    "runtimes/jvm/src/com/flint/rt/Conc.java",
    "runtimes/clr/src/rt/Str.cs", "runtimes/clr/src/rt/Bytes.cs",
    "runtimes/clr/src/rt/Conc.cs",
]

bad, count = [], 0
for rel in SCAN:
    path = os.path.join(HERE, rel)
    if not os.path.exists(path):
        continue
    for i, line in enumerate(open(path, encoding='utf-8'), 1):
        st = line.strip()
        if st.startswith('//') or st.startswith('*') or st.startswith('///'):
            continue
        m = CALL.search(line)
        if not m:
            continue
        name = m.group(1)
        # The definition itself, not a call of it.
        if re.search(r'(fn|static long|public static long)\s+' + re.escape(name.split('.')[-1]), line):
            continue
        # `string_arg` IS the flatten, one line down; its own body is not a
        # separate site to justify.
        if rel == "runtime/src/rt.rs":
            continue
        count += 1
        key = (rel, name)
        if key not in ALLOWED and (rel, name.split('.')[-1]) not in ALLOWED:
            bad.append("%s:%d  %s" % (rel, i, st[:96]))

if bad:
    print("check-flattens: %d unjustified materialisation(s)" % len(bad))
    for b in bad:
        print("  " + b)
    print("\nEvery flatten spends the sharing a rope exists for. Either descend")
    print("instead -- `append_range`, `rope_slice`, `rope_bytes_at`, `b_slice` --")
    print("or add the site to JUSTIFIED in bin/check_flattens.py with a reason.")
    sys.exit(1)
print("flattens: %d materialisation sites, every one justified" % count)
