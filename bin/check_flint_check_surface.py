#!/usr/bin/env python3
"""`flint.check`'s ON and OFF variants publish the same names, the same kind.

`DECISIONS.md#checks`: since the maintainer's 2026-10-07 revision,
`lib/stdcore/flint/check.fln` is one file whose public names each pick their ON or
OFF body via `#?(:flint/check A :default B)`, resolved per compile rather
than by the namespace existing or not. "Same public surface" is a claim a
script can check and a sentence cannot: this walks every top-level
`#?(:flint/check .. :default ..)` form in the file (the ones that actually
HAVE a `:default` -- a `#?(:flint/check ..)` with no `:default`, like the
private rendering helpers, is ON-only machinery and not part of the public
pairing) and asserts that the two branches define the exact same set of
(name, kind) pairs, where kind is `fn` for `defn`/`defn-` and `macro` for
`defmacro`. A name that is a macro on one side and a function on the other
is exactly the defect this exists to catch -- it compiles, and the next
caller writes code that works in one build and not the other.

`test-var?` has no `#?` at all (deliberately: it is one pure function,
identical in both builds), so it never appears here, and that absence is
correct, not a gap -- there is nothing to diverge.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# `lib/stdcore/flint/check.fln` moved to `lib/stdcore/flint/check.fln` -- same
# namespace, now in the stdlib's `.fln`-only root (AGENTS.md's restructure
# note).
SRC = os.path.join(ROOT, "lib", "stdcore", "flint", "check.fln")

DEF_RE = re.compile(r"\(\s*(defn-?|defmacro)\s+\^?\{?\s*\(?\s*([A-Za-z0-9!?*<>=_+./-]+)")
# A `defprotocol` method signature: `(name [args ..] "doc"?)` directly inside
# the protocol's own body. Scoped to callers that already know the branch
# contains a `defprotocol`, so this does not need to exclude `extend-protocol`
# bodies (`:fn (check [f args] ..)`) itself -- that form is handled by its own
# explicit skip in `names` below, since an `extend-protocol` IMPLEMENTATION is
# not a second declaration of the method's name/kind.
PROTOCOL_METHOD_RE = re.compile(r"\n\s+\(([a-z][A-Za-z0-9!?*<>=_+-]*)\s*\[")


def names(branch_text):
    """{(name, kind), ..} of every def directly inside a branch's text.

    A protocol method (declared inside `defprotocol`) counts as `fn`: from a
    caller's seat `(check p args)` is one ordinary call, dispatched rather
    than special, so the OFF variant mirroring it as a plain function is the
    SAME kind, not a different one."""
    out = set()
    for m in DEF_RE.finditer(branch_text):
        form, name = m.group(1), m.group(2)
        kind = "macro" if form == "defmacro" else "fn"
        out.add((name, kind))
    if "(defprotocol" in branch_text:
        proto_body_start = branch_text.index("(defprotocol")
        # Only the protocol's own body -- an `extend-protocol` elsewhere in
        # the same branch is a separate top-level form in this file and is
        # not reached by this regex unless it is textually inside this one,
        # which it never is here.
        for m in PROTOCOL_METHOD_RE.finditer(branch_text[proto_body_start:]):
            out.add((m.group(1), "fn"))
    return out


def split_top(text):
    """Every top-level form in `text`, as raw strings, by paren-depth."""
    forms = []
    depth = 0
    start = None
    in_str = False
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if in_str:
            if c == "\\":
                i += 1
            elif c == '"':
                in_str = False
        elif c == '"':
            in_str = True
        elif c == ";":
            while i < n and text[i] != "\n":
                i += 1
            continue
        elif c == "(":
            if depth == 0:
                # `#?(` is one reader-macro form; keep the `#?` with it so a
                # caller can tell a dispatch form from a plain list.
                start = i - 2 if text[max(0, i - 2):i] == "#?" else i
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0 and start is not None:
                forms.append(text[start:i + 1])
                start = None
        i += 1
    return forms


def first_form_at(text, i):
    """The one form (paren-delimited, or a bare keyword/symbol run) starting
    at the first non-whitespace position at or after `i`, as `(form, end)`."""
    while i < len(text) and text[i] in " \t\n\r":
        i += 1
    if i >= len(text):
        return None, i
    if text[i] != "(":
        j = i
        while j < len(text) and text[j] not in " \t\n\r()":
            j += 1
        return text[i:j], j
    depth = 0
    j = i
    in_str = False
    while j < len(text):
        c = text[j]
        if in_str:
            if c == "\\":
                j += 2
                continue
            if c == '"':
                in_str = False
        elif c == '"':
            in_str = True
        elif c == ";":
            while j < len(text) and text[j] != "\n":
                j += 1
            continue
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return text[i:j + 1], j + 1
        j += 1
    raise ValueError(f"unbalanced parens from {i}")


def branches_of(form):
    """For a top-level `#?(:flint/check A :default B)` form,
    `(A-text, B-text)`, or `None` if it has no `:default` (ON-only
    machinery, not a public pair)."""
    if not form.startswith("#?("):
        return None
    inner = form[3:-1]  # drop `#?(` and the final `)`
    tag, after_tag = first_form_at(inner, 0)
    if tag != ":flint/check":
        return None
    on_form, after_on = first_form_at(inner, after_tag)
    default_kw, after_default_kw = first_form_at(inner, after_on)
    if default_kw != ":default":
        return None
    off_form, _ = first_form_at(inner, after_default_kw)
    return on_form, off_form


def main():
    text = open(SRC, encoding="utf-8").read()
    forms = split_top(text)
    pairs = [branches_of(f) for f in forms]
    pairs = [p for p in pairs if p is not None]
    if not pairs:
        print(f"FAILED: no #?(:flint/check .. :default ..) pairs found in {SRC}")
        sys.exit(1)
    bad = []
    total_on, total_off = set(), set()
    for on_text, off_text in pairs:
        on_names, off_names = names(on_text), names(off_text)
        total_on |= on_names
        total_off |= off_names
        if on_names != off_names:
            bad.append((on_names, off_names))
    if bad:
        print("FAILED: flint.check's ON and OFF variants diverge:")
        for on_names, off_names in bad:
            only_on = on_names - off_names
            only_off = off_names - on_names
            if only_on:
                print(f"    only in the ON variant:  {sorted(only_on)}")
            if only_off:
                print(f"    only in the OFF variant: {sorted(only_off)}")
        sys.exit(1)
    print(f"  ok   flint.check's ON and OFF variants publish the same "
          f"{len(total_on)} name(s), same kind: {sorted(total_on)}")


if __name__ == "__main__":
    main()
