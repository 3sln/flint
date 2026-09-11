"""Every materialisation of a rope must say why it is one.

`DECISIONS.md#strings-and-matching` buys two things with a tree: concatenation that is not
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
    # These became VISIBLE when the detector learned `text`/`Text`, and they
    # were always here. Both uses in `Conc` are the same boundary: a port's
    # NAME and LABEL are strings the host is shown -- in a deadlock report, or
    # to find a virtual namespace -- and a payload crossing a port becomes wire
    # bytes. Neither can stay a tree, because neither stays in this heap.
    "a port's name, its label and its payload all leave the heap": [
        ("runtimes/jvm/src/com/flint/rt/Conc.java", "Str.text"),
        ("runtimes/clr/src/rt/Conc.cs", "Str.Text"),
    ],
    "hashing caches the flat form, so a rope used as a map key pays once": [
        ("runtime/src/eq.rs", "flatten"),
        ("runtimes/jvm/src/com/flint/rt/Str.java", "flatten"),
        ("runtimes/clr/src/rt/Str.cs", "Flatten"),
    ],
    # WAS: "a search needs contiguous bytes, and caching pays off when the
    # same string is searched again". That justification was WRONG, and it is
    # worth saying how rather than just deleting it.
    #
    # `strings-and-matching` lists `index-of` under what must WALK the structure -- "none of
    # them needs contiguous bytes" -- and warns in the next breath that "a
    # rope that flattens on every `index-of` passes every correctness test and
    # is slower than the flat string it replaced". The conclusion that got
    # borrowed here, "flatten before matching", is from `strings-and-matching` §3 and is about
    # HOST REGEX ENGINES, which genuinely do want a `&str`/`string`. A
    # substring search does not, and the search is `kin/ropefind.kin` now.
    #
    # It also hid a divergence: this file says a justification holding for Rust
    # and not for the JVM is a port that has drifted, and this entry was listed
    # for Rust ALONE. The ports never called anything named `flatten` -- they
    # built a host `String` instead -- so the checker could not see that they
    # were materialising too.
    "the non-rope fallthrough, where `string_arg` cannot flatten anything": [
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

# `value_text`, `text` and `Text` ARE MATERIALISATIONS TOO, and leaving them out
# is how `str_cmp` flattened both operands of every string comparison on all
# three runtimes without this gate noticing. It scans `eq.rs` -- it just did not
# know the name the copy was reached by. A detector that only knows the names it
# was told is worth less than it looks, so the rule is now: anything that hands
# back contiguous bytes counts, whatever it is called.
CALL = re.compile(r'\b(string_arg|flatten|Flatten|Str\.flatten|Str\.Flatten'
                  r'|value_text|Str\.text|Str\.Text)\s*\(')
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
