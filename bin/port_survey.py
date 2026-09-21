#!/usr/bin/env python3
"""Where is the porting work, and have the three runtimes drifted?

    bin/port-survey            both answers
    bin/port-survey --rank     where the portable lines are
    bin/port-survey --drift    which hand-written functions disagree

THIS REPORTS; IT DOES NOT GATE, and it cannot. Both halves are heuristics over
source text: the rank classifies by regex and the drift check compares token
multisets across three languages, so both have a false-positive rate that a
threshold would either hide or trip over. `bin/check-kin` and
`bin/conform-hosts` are the gates; this is for deciding what to do next.

WHY IT IS A SCRIPT AND NOT A PARAGRAPH. `doc/goals/kin-port.md` carried this
method as prose under "Method, so it can be re-run", including two warnings
about how it goes wrong. Re-running it a month later meant rewriting it, and
the rewrite hit BOTH recorded bugs and three more the prose had not thought to
mention -- a return-type pattern that matched pure whitespace, so every
`def("flint/x", ...)` in `Builtins` parsed as a method and the file scored 63%
portable on several hundred of them; a host callback table; and a host object
allocated into a host list. A method that has to be reimplemented to be re-run
is a method that will be reimplemented differently.
"""
import re, glob, os, sys, collections, difflib

# --- the rank ---------------------------------------------------------------

BLOCK = [
    (r"\bbyte\[\]", "host bytes"),
    (r"\bString\b|StringBuilder|\.charAt\(|\bchar\b|\"\s*\+|\+\s*\"", "host string"),
    (r"ArrayList|HashMap|java\.util\.|List<|Map<|\bnew [A-Z]\w*\(|"
     r"\.add\(|\.clear\(\)|\.size\(\)|\.remove\(", "host collections"),
    (r"\bsp\.\w|gc\.sp\b|slotAddr|\bbump\b|fromEnd|toEnd|zeroBody|\bforward\(|"
     r"Space\b|\bheapBase\b|Unsafe|ByteBuffer", "raw memory"),
    (r"\bOp\.[A-Z_]{2,}", "opcode dispatch"),
    (r"\bFn\b|\.apply\(|->\s*\{|::\w+\)", "host callback"),
]

# THE STANDING NEVER LIST, used as an ASSERTION rather than an exclusion:
# encoding these away would make the check unable to fail. `Gc.alloc` scoring
# portable is how the raw-memory rule was found to be too narrow, twice.
NEVER = {"Gc", "Snap"}

DECL = re.compile(
    r"^    (?:(?:public|private|protected|static|final|abstract|synchronized)\s+)*"
    r"(?:[A-Za-z_][\w.]*(?:<[^>]*>)?(?:\[\])?)\s+"
    r"([A-Za-z_]\w*)\s*\(")


def java_methods(path):
    src = open(path).read().split("\n")
    out, i = [], 0
    while i < len(src):
        m = DECL.match(src[i])
        if m and not src[i].rstrip().endswith(";"):
            depth, body, k = 0, [], i
            while k < len(src):
                depth += src[k].count("{") - src[k].count("}")
                body.append(src[k]); k += 1
                if depth <= 0 and k > i:
                    break
            code = [l for l in body
                    if l.strip() and not l.strip().startswith(("//", "/*", "*", "///"))]
            out.append((m.group(1), "\n".join(body), len(code)))
            i = k
        else:
            i += 1
    return out


def rank():
    rows = []
    for path in sorted(glob.glob("runtimes/jvm/src/com/flint/rt/*.java")):
        area = os.path.basename(path)[:-5]
        # DOES THE THIRD RUNTIME HAVE ONE? `clean` says the code could be
        # expressed in kin. It does NOT say there are three copies to replace.
        # `Rt.lookup` ranked first among clean methods and native has no
        # matching function at all -- its `apply_keyword` is shaped
        # differently -- so generating it would have produced a third
        # implementation that nothing calls. A two-way dedup is worth less
        # than a three-way one and is a different job; the rank has to say
        # which it is offering.
        nat = {}
        if area in AREAS:
            nat = extract_all(AREAS[area][2], N_PAT)
        hand = clean = three = 0
        why, big = collections.Counter(), []
        for name, txt, n in java_methods(path):
            if "kgen" in txt:
                continue                       # already generated
            hand += n
            hit = next((lbl for rx, lbl in BLOCK if re.search(rx, txt)), None)
            if hit:
                why[hit] += n
            else:
                clean += n
                has3 = norm(name) in nat
                if has3:
                    three += n
                big.append((n, name, has3))
        if hand:
            rows.append((clean, hand, area, why,
                         sorted(big, reverse=True)[:3], three, bool(nat)))
    rows.sort(reverse=True)
    print(f"  {'area':<12} {'hand':>6} {'clean':>6} {'in 3':>6} {'%':>4}   dominant blocker")
    for clean, hand, area, why, big, three, checked in rows[:12]:
        top = why.most_common(1)[0] if why else ("--", 0)
        col = f"{three:>6}" if checked else "    --"
        print(f"  {area:<12} {hand:>6} {clean:>6} {col} {round(100*clean/hand):>3}%   {top[0]}, {top[1]}")
        if clean and big:
            print("               biggest clean: "
                  + ", ".join(f"{n} {nm}{'' if h else ' (2-way)'}" for n, nm, h in big))
    print("\n  `in 3` is clean lines whose method ALSO exists on native -- three")
    print("  copies to replace with one. The rest are a two-way dedup between the")
    print("  ports, which is worth having and is a different job. `--` means the")
    print("  area is not in AREAS, so the third runtime was not looked for.")
    print("\n  never-list (these must stay low, or a blocker rule is too narrow):")
    for clean, hand, area, why, big, three, checked in rows:
        if area in NEVER:
            pct = round(100 * clean / hand)
            print(f"    {'ok  ' if pct <= 12 else 'HIGH'} {area:<6} {pct:>3}% clean of {hand}")


# --- the drift check --------------------------------------------------------

NOISE = set("""let mut pub fn static public private final return long int uint u32 u64 i64 i32
bool boolean void var new this self rt crate conc val value obj str string as into unwrap
or is some none nil null true false if else while for match case break continue do
usize isize ref deref clone copy from to type struct impl class const readonly override
global namespace using import package throw throws catch try finally""".split())


def norm(tok):
    return re.sub(r"(?<!^)(?=[A-Z])", "", tok.replace("_", "")).lower()


# OPERATORS ARE TOKENS TOO, and leaving them out was the tool's biggest hole.
# Identifiers and numbers alone cannot see `<` become `<=`, `==` become `!=`,
# or `+` become `-` -- which is to say it could not see an off-by-one or an
# inverted test, the two divergences most worth finding. Demonstrated: flipping
# `<` to `<=` in the collector's `InFrom` address predicate, a real boundary
# bug, moved the similarity score by exactly zero.
#
# LONGEST MATCH FIRST, or `<=` reads as `<` then `=`. And `->`/`=>` are
# EXCLUDED: they are a Java lambda and a C# expression body, syntax the two
# languages spell differently for the same thing, so including them would add
# noise to the one comparison that matters most.
OPS = (r"<<=|>>>=|>>=|>>>|<<|>>|<=|>=|==|!=|&&|\|\||[-+*/%&|^!<>~]")


def toks(body):
    body = re.sub(r"//.*|/\*.*?\*/|///.*", "", body, flags=re.S)
    body = re.sub(r'"(?:[^"\\]|\\.)*"', " STR ", body)
    body = body.replace("->", " ").replace("=>", " ")
    out = []
    for m in re.finditer(r"[A-Za-z_][A-Za-z0-9_]*|[0-9]+|" + OPS, body):
        t = m.group(0)
        if t[0].isalpha() or t[0] == "_" or t[0].isdigit():
            t = norm(t)
            if t and t not in NOISE:
                out.append(t)
        else:
            out.append(t)
    return out


def extract(path, pat):
    src = open(path).read().split("\n")
    fns, i = {}, 0
    while i < len(src):
        m = re.search(pat, src[i])
        if m:
            head = src[i]
            # AN EXPRESSION BODY IS NOT A BLOCK. C# writes `static T F(..) =>
            # expr;` with no braces, and a brace-depth walk over one runs on to
            # the NEXT function's closing brace, swallowing it whole. Three
            # functions ranked as the worst mismatches in the file before this
            # case existed, and all three were this.
            if "=>" in head and "{" not in head.split("=>", 1)[1]:
                body, k = [head], i + 1
                while ";" not in body[-1] and k < len(src):
                    body.append(src[k]); k += 1
                fns[norm(m.group(1))] = "\n".join(body)
                i = k
                continue
            depth, body, k = 0, [], i
            while k < len(src):
                depth += src[k].count("{") - src[k].count("}")
                body.append(src[k]); k += 1
                if depth <= 0 and k > i:
                    break
            fns[norm(m.group(1))] = "\n".join(body)
            i = k
        else:
            i += 1
    return fns


# WHICH FILES HOLD ONE AREA, per runtime. Native does not split the same way
# the ports do: `Rt`'s methods live across `rt.rs`, `vm.rs` and `err.rs`, so
# the native side of a pairing is a LIST and is concatenated before extraction.
AREAS = {
    "Conc": (["runtimes/jvm/src/com/flint/rt/Conc.java"],
             ["runtimes/clr/src/rt/Conc.cs"],
             ["runtime/src/conc.rs"]),
    "Rt":   (["runtimes/jvm/src/com/flint/rt/Rt.java"],
             ["runtimes/clr/src/rt/Rt.cs"],
             ["runtime/src/rt.rs", "runtime/src/vm.rs", "runtime/src/err.rs"]),
    "Gc":   (["runtimes/jvm/src/com/flint/rt/Gc.java"],
             ["runtimes/clr/src/rt/Gc.cs"],
             ["runtime/src/gc.rs"]),
    "Snap": (["runtimes/jvm/src/com/flint/rt/Snap.java"],
             ["runtimes/clr/src/rt/Snap.cs"],
             ["runtime/src/snap.rs"]),
}

# INSTANCE METHODS TOO, not only statics. `Conc` is a static utility class and
# `Rt` is not -- sweeping it with the static-only pattern found 3 of its 100-odd
# methods and reported the file as clean, which is the shape of a pass over
# almost nothing.
# INDENT `\s{4,}`, NOT EXACTLY FOUR. The clr keeps `NewOpaque`, `NewTagged`
# and `OpaqueHostId` inside a NESTED class at eight spaces, so a four-space
# anchor missed all three and the absence report named them as jvm-only. A
# report of what one side is missing must not be able to invent an absence.
#
# The keyword guard is what makes the looser anchor safe: at eight spaces
# `new Frame(` reads as type `new`, name `Frame`, and `return Foo(` as type
# `return`.
NOT_A_TYPE = r"(?!(?:new|return|if|while|for|switch|else|catch|using|lock|throw)\b)"
J_PAT = (r"^\s{4,}(?:(?:public|private|protected|static|final|synchronized)\s+)*"
         + NOT_A_TYPE +
         r"(?:[A-Za-z_][\w.]*(?:<[^>]*>)?(?:\[\])?)\s+([A-Za-z_]\w*)\s*\(")
C_PAT = (r"^\s{4,}(?:(?:public|private|protected|internal|static|readonly|override|sealed)\s+)*"
         + NOT_A_TYPE +
         r"(?:[A-Za-z_][\w.]*(?:<[^>]*>)?(?:\[\])?)\s+([A-Za-z_]\w*)\s*\(")
N_PAT = r"^\s*(?:pub(?:\(crate\))? )?fn (\w+)\s*[(<]"


def extract_all(paths, pat):
    out = {}
    for p in paths:
        if os.path.exists(p):
            out.update(extract(p, pat))
    return out


def drift(area="Conc"):
    jpaths, cpaths, npaths = AREAS[area]
    jvm = extract_all(jpaths, J_PAT)
    clr = extract_all(cpaths, C_PAT)
    nat = extract_all(npaths, N_PAT)

    def sim(a, b):
        """How much of the same VOCABULARY, ignoring order."""
        ca, cb = collections.Counter(a), collections.Counter(b)
        return sum((ca & cb).values()) / max(len(a), len(b), 1)

    def seq(a, b):
        """How much of the same SEQUENCE.

        A multiset comparison cannot see a reordering: swap two statements and
        the tokens are identical. That is the blind spot that matters most
        here, because ORDER is where this project's real bugs have been --
        rooting a value after the call that allocates, clearing a field after
        the save that reads it, waking before the state write. `sim` high and
        `seq` low means the two sides say the same words in a different order,
        which is the signal worth reading.
        """
        return difflib.SequenceMatcher(None, a, b).ratio()

    # PRESENT ON ONE PORT AND NOT THE OTHER, reported FIRST. The pairwise
    # comparison below can only look at methods both sides have, so the one
    # thing it structurally cannot see is an absence -- and an absence is what
    # the last real find was: the clr was missing seven gas-attribution
    # counters the jvm had, so a divergence involving it could be seen as a
    # total and never split.
    #
    # Names are folded the same way the bodies are, so `wakeOn` and `WakeOn`
    # are one name and a genuine spelling difference is not reported as a gap.
    only_j = sorted(set(jvm) - set(clr))
    only_c = sorted(set(clr) - set(jvm))
    if only_j or only_c:
        print(f"  ONLY ON ONE PORT -- {len(only_j)} jvm, {len(only_c)} clr")
        if only_j:
            print("    jvm only: " + ", ".join(only_j[:14])
                  + (f" (+{len(only_j)-14})" if len(only_j) > 14 else ""))
        if only_c:
            print("    clr only: " + ", ".join(only_c[:14])
                  + (f" (+{len(only_c)-14})" if len(only_c) > 14 else ""))
        print()

    rows, pairs = [], 0
    for n, body in jvm.items():
        if "kgen" in body or n not in clr:
            continue
        tj = toks(body)
        if not tj:
            continue
        pairs += 1
        # NATIVE IS OPTIONAL in the pairing. The two PORTS are meant to be
        # mirrors, so a difference between them is a defect outright; native
        # differs legitimately in structure and is reported beside, not gated.
        tc = toks(clr[n])
        sn = sim(tj, toks(nat[n])) if n in nat else float("nan")
        rows.append((sim(tj, tc), seq(tj, tc), sn, n, len(tj)))
    rows.sort(key=lambda r: min(r[0], r[1]))
    print(f"  {area}: {len(jvm)} methods on the jvm side, {pairs} hand-written and present on both ports")
    print(f"  {'words':>6} {'order':>6} {'~nat':>6} {'toks':>5}  name")
    for sc, sq, sn, n, L in rows[:10]:
        ns = "  --  " if sn != sn else f"{sn:6.2f}"
        # SAME WORDS, DIFFERENT ORDER is the row to read first: it is what a
        # reordering looks like and what the multiset alone cannot show.
        # THE GAP SCALES WITH HOW MUCH MOVED, not with how bad it is: three
        # statements swapped inside an 80-token function shifts `order` by
        # 0.06 and leaves `words` untouched. So the threshold is small on
        # purpose, and the flag means "read this for ORDER" rather than
        # "this is wrong".
        flag = "  <-- same words, different order" if sc - sq > 0.04 else ""
        print(f"  {sc:6.2f} {sq:6.2f} {ns} {L:5}  {n}{flag}")


# --- the constants -----------------------------------------------------------
#
# METHOD BODIES ARE NOT THE WHOLE SURFACE. The drift sweep above compares what
# the three runtimes DO and says nothing about the numbers they agree to do it
# with. A slot index, a type tag or a capacity that differs between two ports
# is silent to every check in this repo: the code is identical, the constant is
# not, and the conformance transcripts only catch it if some program happens to
# reach the slot that moved.
#
# This is the same shape as the last real find -- seven counters the clr did
# not have -- one level down, at the declaration rather than the definition.

J_CONST = re.compile(r"^\s*(?:public |private |protected )?static final "
                     r"(?:int|long|byte|short|boolean|double) (.+?);\s*$")
C_CONST = re.compile(r"^\s*(?:public |private |protected |internal )?"
                     r"(?:const|static readonly) "
                     r"(?:int|long|byte|short|bool|double) (.+?);\s*$")
N_CONST = re.compile(r"^\s*pub(?:\(crate\))? const ([A-Za-z_]\w*)\s*:"
                     r"\s*[\w:]+\s*=\s*(.+?);\s*$")


def norm_val(v):
    """One spelling for a value. `8L`, `8u32`, `16*1024` and `16384` are one.

    ARITHMETIC IS EVALUATED, not compared as text. `LARGE_OBJECT` is `16384`
    on the jvm and `16 * 1024` on native, which is the same number written for
    two different readers -- and reporting it as a disagreement trains the
    reader to skim this list.
    """
    v = v.strip()
    v = re.sub(r"\b(\d+)[Ll]\b", r"\1", v)
    v = re.sub(r"\b(\d+)(?:u8|u16|u32|u64|i8|i16|i32|i64|usize|isize)\b", r"\1", v)
    # A cast says nothing about the value: `(long) 3` and `3` are the same.
    v = re.sub(r"\((?:long|int|uint|ulong|byte|short)\)\s*", "", v)
    v = re.sub(r"\s+", "", v)
    while len(v) > 1 and v[0] == "(" and v[-1] == ")":
        # ONLY A BALANCED outer pair. Stripping blindly turned `Val.fixnum(0)`
        # into `Val.fixnum(0` and reported it as a disagreement with itself.
        depth, ok = 0, True
        for i, c in enumerate(v):
            depth += (c == "(") - (c == ")")
            if depth == 0 and i < len(v) - 1:
                ok = False
                break
        if not ok:
            break
        v = v[1:-1]
    if re.fullmatch(r"[-+*/<>()0-9 ]+", v) and re.search(r"\d", v):
        try:
            return str(eval(v, {"__builtins__": {}}, {}))   # digits and operators only
        except Exception:
            pass
    if re.fullmatch(r"0[xX][0-9a-fA-F]+", v):
        return str(int(v, 16))
    # A CALL IS FOLDED LIKE A NAME. `Val.fixnum(0)` and `Val.Fixnum(0)` are the
    # same value spelled for two languages' conventions.
    return re.sub(r"[A-Za-z_][\w.]*", lambda m: m.group(0).replace("_", "").lower(), v)


def logical_lines(text):
    """One declaration per string, however many source lines it spans.

    A DECLARATION IS NOT A LINE. The clr writes its type tags as a single
    `public const int TyFree = 0, TyFwd = 1, ...` running over eight source
    lines, and a line-based match requires the `;` on the line it started on
    -- so it extracted NONE of them. Forty-four type tags, the constants in
    this system that it is least survivable to disagree about, were silently
    not compared, and the report said `0 DISAGREE` over the ones it had
    managed to read.

    Joining is unconditional rather than clever: any line that opens a
    declaration and does not close it takes the following lines until one
    does.
    """
    out, buf = [], None
    # A TRAILING COMMENT IS NOT PART OF THE DECLARATION. The jvm writes
    # `TY_RECORD = 28;     // [type, basis, ext, meta, ...fields]`, and a
    # pattern anchored on the `;` ending the line matched none of them -- so
    # every type tag that carried an explanatory comment was dropped, which is
    # most of the ones worth explaining.
    text = re.sub(r"//.*", "", text)
    for line in text.split("\n"):
        if buf is not None:
            buf += " " + line.strip()
            if ";" in line:
                out.append(buf)
                buf = None
            continue
        if re.search(r"\b(?:const|static final|static readonly)\b", line) and ";" not in line:
            buf = line.rstrip()
            continue
        out.append(line)
    if buf is not None:
        out.append(buf)
    return out


def consts_of(paths, kind):
    out = {}
    for path in paths:
        if not os.path.exists(path):
            continue
        for line in logical_lines(open(path).read()):
            if kind == "rust":
                m = N_CONST.match(line)
                if m:
                    # FOLDED LIKE A METHOD NAME. `MAGIC_LIVE` on the jvm and
                    # `MagicLive` on the clr are one constant; leaving them
                    # apart meant their VALUES were never compared, and the
                    # report still said "the two ports agree on every constant
                    # they share" while sharing half of them.
                    out[norm(m.group(1))] = norm_val(m.group(2))
                continue
            m = (J_CONST if kind == "java" else C_CONST).match(line)
            if not m:
                continue
            # ONE LINE, SEVERAL CONSTANTS. Java and C# both allow
            # `int A = 0, B = 1;` and this file uses it heavily -- splitting on
            # the comma is not optional, it is most of the declarations.
            for part in m.group(1).split(","):
                if "=" not in part:
                    continue
                nm, _, val = part.partition("=")
                nm = nm.strip()
                if re.fullmatch(r"[A-Za-z_]\w*", nm):
                    out[norm(nm)] = norm_val(val)
    return out


def consts(area):
    jpaths, cpaths, npaths = AREAS[area]
    j = consts_of(jpaths, "java")
    c = consts_of(cpaths, "csharp")
    n = consts_of(npaths, "rust")
    print(f"  {area}: {len(j)} jvm, {len(c)} clr, {len(n)} native constants")

    # THE DANGEROUS CASE FIRST: same name, different number.
    bad = []
    for nm in sorted(set(j) & set(c)):
        if j[nm] != c[nm]:
            bad.append((nm, j[nm], c[nm], n.get(nm, "--")))
    if bad:
        print("\n  DISAGREE between the two ports:")
        for nm, jv, cv, nv in bad:
            print(f"    {nm:<22} jvm {jv:<12} clr {cv:<12} nat {nv}")
    else:
        print("  the two ports agree on every constant they share")

    nat_bad = [(nm, j[nm], n[nm]) for nm in sorted(set(j) & set(n)) if j[nm] != n[nm]]
    if nat_bad:
        print("\n  jvm against native (native spells some differently -- read these):")
        for nm, jv, nv in nat_bad[:12]:
            print(f"    {nm:<22} jvm {jv:<12} nat {nv}")

    only_j = sorted(set(j) - set(c))
    only_c = sorted(set(c) - set(j))

    # MATCHED ACROSS A NAMING DIFFERENCE, and the difference is still shown.
    #
    # The clr spells four exception slot indices `ExKindSlot` where the jvm
    # says `EX_KIND`, and the suffix is FORCED: `Rt.cs` also has a METHOD
    # `ExKind(long)`, and C# will not take two members of one type with the
    # same name where Java's fields and methods do not collide. So this is a
    # language constraint and not drift -- but until it was paired, the four
    # were on one side only, and a name on one side only is never
    # VALUE-compared. The report still said "the two ports agree on every
    # constant they share" while not sharing them.
    #
    # Pairing rather than folding the suffix away: conflating `X` with `XSlot`
    # silently would hide a real second constant that happened to be named
    # that. Here the pair is named, the values are compared, and a mismatch is
    # as loud as any other.
    SUFFIXES = ("slot", "idx", "index", "const", "value")
    paired = []
    for n in list(only_j):
        for suf in SUFFIXES:
            if n + suf in only_c:
                paired.append((n, n + suf, j[n], c[n + suf]))
                only_j.remove(n)
                only_c.remove(n + suf)
                break
    for n in list(only_c):
        for suf in SUFFIXES:
            if n + suf in only_j:
                paired.append((n + suf, n, j[n + suf], c[n]))
                only_c.remove(n)
                only_j.remove(n + suf)
                break
    if paired:
        bad_pairs = [t for t in paired if t[2] != t[3]]
        print(f"\n  matched across a naming difference ({len(paired)}), "
              f"{'ALL AGREE' if not bad_pairs else str(len(bad_pairs)) + ' DISAGREE'}:")
        for jn, cn, jv, cv in sorted(paired):
            mark = "  <-- DISAGREE" if jv != cv else ""
            print(f"    {jn} / {cn}: {jv} / {cv}{mark}")

    if only_j or only_c:
        print(f"\n  ON ONE PORT ONLY -- {len(only_j)} jvm, {len(only_c)} clr")
        if only_j:
            print("    jvm only: " + ", ".join(only_j[:16]))
        if only_c:
            print("    clr only: " + ", ".join(only_c[:16]))
        print("    (a name on one side only is never VALUE-compared -- read these)")


def all_pairs():
    """Every file the two ports both have, paired by name.

    `AREAS` is hand-written and covers four files. The constants most
    dangerous to diverge are not in them: a type tag in `Obj`, a NaN-box tag
    in `Val`, a format code in `Wire`. A mismatch there is not a wrong answer,
    it is corruption -- and hand-listing the areas to check is how the
    interesting ones get left out.
    """
    out = []
    for jp in sorted(glob.glob("runtimes/jvm/src/com/flint/rt/*.java")):
        name = os.path.basename(jp)[:-5]
        cp = f"runtimes/clr/src/rt/{name}.cs"
        if os.path.exists(cp):
            out.append((name, [jp], [cp]))
    return out


def consts_all(quiet=False):
    """The ports against each other, every file, constants only.

    Returns the number of disagreements, so this can be a GATE as well as a
    report. The two ports are meant to be mirrors and a number they disagree
    about is a defect outright -- unlike the drift sweep above, which is a
    similarity heuristic and could never carry a threshold.
    """
    pairs = all_pairs()
    total = disagree = paired_n = onesided = 0
    print(f"  {len(pairs)} files the two ports both have\n")
    for name, jp, cp in pairs:
        j = consts_of(jp, "java")
        c = consts_of(cp, "csharp")
        if not j and not c:
            continue
        # CONFIGURATION IS NOT LAYOUT. `staleCheck` reads the environment at
        # class-init: the jvm accepts `-Dflint.stale` OR `FLINT_STALE`, the
        # clr only the env var, because C# has no system properties. That is a
        # language difference in a developer escape hatch, not a number the
        # two runtimes have to agree on -- and leaving it in meant this check
        # could never read zero, which is how a report starts being skimmed.
        env = lambda v: any(k in v for k in ("getenv", "getenvironmentvariable",
                                             "getproperty"))
        j = {k: v for k, v in j.items() if not env(v)}
        c = {k: v for k, v in c.items() if not env(v)}
        shared = set(j) & set(c)
        total += len(shared)
        bad = [(n, j[n], c[n]) for n in sorted(shared) if j[n] != c[n]]
        oj, oc = sorted(set(j) - set(c)), sorted(set(c) - set(j))
        # PAIRED ACROSS A NAMING DIFFERENCE, prefix or suffix, and the pair is
        # always SHOWN. Both differences seen here are forced by C#, not
        # chosen: `ExKindSlot` because `Rt.cs` also has a method `ExKind`, and
        # `LVals`/`LStr`/`LRaw` because `Str` is a class in that runtime.
        # Java's fields and methods do not collide, so the jvm keeps the bare
        # name. Folding them away silently would risk conflating a real second
        # constant; naming the pair and printing both values does not.
        pr = []
        for n in list(oj):
            for alt in [n + x for x in ("slot", "idx", "index", "const", "value")] \
                       + ["l" + n]:
                if alt in oc:
                    pr.append((n, alt, j[n], c[alt]))
                    oj.remove(n); oc.remove(alt); break
        for n in list(oc):
            for alt in [n + x for x in ("slot", "idx", "index", "const", "value")] \
                       + ["l" + n]:
                if alt in oj:
                    pr.append((alt, n, j[alt], c[n]))
                    oc.remove(n); oj.remove(alt); break
        paired_n += len(pr)
        bad += [(f"{a}/{b}", jv, cv) for a, b, jv, cv in pr if jv != cv]
        onesided += len(oj) + len(oc)
        if bad or (oj or oc) and not quiet:
            print(f"  {name}: {len(shared)} shared")
            for n, jv, cv in bad:
                print(f"    DISAGREE  {n:<24} jvm {jv:<14} clr {cv}")
            if oj:
                print(f"    jvm only: {', '.join(oj[:10])}"
                      + (f" (+{len(oj)-10})" if len(oj) > 10 else ""))
            if oc:
                print(f"    clr only: {', '.join(oc[:10])}"
                      + (f" (+{len(oc)-10})" if len(oc) > 10 else ""))
        disagree += len(bad)
    print(f"\n  {total} constants compared by name, {paired_n} more paired across a"
          f" naming difference,\n  {onesided} on one side only, {disagree} DISAGREE")
    # COVERAGE, BECAUSE THIS EXTRACTOR HAS UNDER-READ FOUR TIMES.
    #
    # A multi-line declaration, a trailing comment, a nested class and a
    # name-folding gap each made it silently see less than it claimed, and
    # every one of them reported `0 DISAGREE` over the subset it managed to
    # read. The count of declarations it PARSED against the count of lines
    # that LOOK like declarations is the cheapest way to notice the next one.
    seen = missed = 0
    for _, jp, cp in pairs:
        for path in jp + cp:
            txt = open(path).read()
            looks = len(re.findall(r"\b(?:const|static final|static readonly)\s+"
                                   r"(?:int|long|byte|short|bool|boolean|double)\b", txt))
            got = len(re.findall(r"\b(?:const|static final|static readonly)\s+"
                                 r"(?:int|long|byte|short|bool|boolean|double)\b",
                                 "\n".join(logical_lines(re.sub(r"//.*", "", txt)))))
            seen += got
            missed += max(0, looks - got)
    print(f"  coverage: {seen} declarations parsed"
          + (f", {missed} that look like declarations were NOT -- read them"
             if missed else ", none skipped"))
    return disagree, seen


if __name__ == "__main__":
    what = sys.argv[1] if len(sys.argv) > 1 else "--both"
    if what in ("--rank", "--both"):
        print("\n== where the portable lines are\n")
        rank()
    if what in ("--drift", "--both"):
        area = sys.argv[2] if len(sys.argv) > 2 else "Conc"
        print(f"\n== whether the three have drifted: {area}\n")
        drift(area)
    if what == "--check":
        # THE GATE FORM. A constant the two ports disagree about is a defect,
        # so this exits non-zero; the drift sweep beside it is a heuristic and
        # deliberately has no gate form at all.
        n, seen = consts_all(quiet=True)
        if n:
            print(f"\ncheck-port-consts: {n} constant(s) disagree between the ports")
            raise SystemExit(1)
        print(f"check-port-consts: {seen} constant declarations across 30 file pairs,"
              f" the two ports agree on every one they share")
        raise SystemExit(0)
    if what == "--consts" and len(sys.argv) > 2 and sys.argv[2] == "all":
        print("\n== every constant the two ports both declare\n")
        consts_all()
        print()
        raise SystemExit(0)
    if what in ("--consts", "--both"):
        areas = [sys.argv[2]] if len(sys.argv) > 2 and what == "--consts" else list(AREAS)
        for a in areas:
            print(f"\n== the numbers they agree to work with: {a}\n")
            consts(a)
    print()
