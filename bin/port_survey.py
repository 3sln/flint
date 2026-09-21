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
import re, glob, os, sys, collections

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
        hand = clean = 0
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
                big.append((n, name))
        if hand:
            rows.append((clean, hand, area, why, sorted(big, reverse=True)[:3]))
    rows.sort(reverse=True)
    print(f"  {'area':<12} {'hand':>6} {'clean':>6} {'%':>4}   dominant blocker")
    for clean, hand, area, why, big in rows[:12]:
        top = why.most_common(1)[0] if why else ("--", 0)
        print(f"  {area:<12} {hand:>6} {clean:>6} {round(100*clean/hand):>3}%   {top[0]}, {top[1]}")
        if clean and big:
            print("               biggest clean: "
                  + ", ".join(f"{n} {nm}" for n, nm in big))
    print("\n  never-list (these must stay low, or a blocker rule is too narrow):")
    for clean, hand, area, why, big in rows:
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


def toks(body):
    body = re.sub(r"//.*|/\*.*?\*/|///.*", "", body, flags=re.S)
    body = re.sub(r'"(?:[^"\\]|\\.)*"', " STR ", body)
    return [t for t in (norm(m.group(0))
            for m in re.finditer(r"[A-Za-z_][A-Za-z0-9_]*|[0-9]+", body))
            if t and t not in NOISE]


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


def drift():
    jvm = extract("runtimes/jvm/src/com/flint/rt/Conc.java",
                  r"^    (?:public |private )?static \S+ (\w+)\(")
    clr = extract("runtimes/clr/src/rt/Conc.cs",
                  r"^    (?:public |private |internal )?static \S+ (\w+)\(")
    nat = extract("runtime/src/conc.rs",
                  r"^\s*(?:pub(?:\(crate\))? )?fn (\w+)\s*[(<]")

    def sim(a, b):
        ca, cb = collections.Counter(a), collections.Counter(b)
        return sum((ca & cb).values()) / max(len(a), len(b), 1)

    rows = []
    for n, body in jvm.items():
        if "kgen" in body or n not in clr or n not in nat:
            continue
        tj = toks(body)
        if tj:
            rows.append((sim(tj, toks(clr[n])), sim(tj, toks(nat[n])), n, len(tj)))
    rows.sort()
    print(f"  {len(rows)} functions hand-written in all three of Conc")
    print(f"  {'jvm~clr':>8} {'jvm~nat':>8} {'toks':>5}  name   (read the lowest; the rest are spelling)")
    for sc, sn, n, L in rows[:8]:
        print(f"  {sc:8.2f} {sn:8.2f} {L:5}  {n}")


if __name__ == "__main__":
    what = sys.argv[1] if len(sys.argv) > 1 else "--both"
    if what in ("--rank", "--both"):
        print("\n== where the portable lines are\n")
        rank()
    if what in ("--drift", "--both"):
        print("\n== whether the three have drifted\n")
        drift()
    print()
