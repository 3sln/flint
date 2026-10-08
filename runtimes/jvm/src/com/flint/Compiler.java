package com.flint;

import com.flint.rt.Img;
import com.flint.rt.Rt;
import com.flint.rt.Seqs;
import com.flint.rt.Str;
import com.flint.rt.Val;

/// THE FLINT COMPILER, RUNNING ON THIS RUNTIME.
///
/// The JVM's mirror of the ESM SDK's `Compiler`: that one is
/// `new Compiler(module)` over `dist/flintc.wasm`, this one is
/// `Compiler.of(bytecode)` over `dist/flintc.bytecode`. Both run the SAME
/// compiler -- it is a flint program, and self-hosting on this runtime is gated
/// by `FLINT_SELFHOST=1 ./bin/conform-hosts`.
///
/// ## What this does NOT do, deliberately
///
/// It takes a finished SPEC and does not build one. The ESM `Compiler.compile()`
/// assembles the spec EDN from source files and options -- and so do
/// `cli/src/main.rs`, `bin/flint` and `project.cljc`, which is four
/// implementations of one fact and a hazard `DECISIONS.md#one-dependency-walk`
/// records by name. A fifth would make it worse, so this stops where the
/// duplication would start. Sharing spec construction is its own change: compile
/// `lib/flint/cli.cljc` once and let every door call it, which `ROADMAP.md`
/// carries as the item after `:to :llvm`.
///
/// So the mirror is HALF complete on purpose, and that is the honest half.
///
/// ## The four things a second implementation gets wrong
///
/// Promoted from `runtimes/jvm/test/RtSelfHost.java`, which learnt each of them
/// the hard way and wrote them down:
///
///   * `flint.compiler.selfhost/main` is a VAR, found through the image's var table.
///     `img.entry` is a different function, and calling it hands the compiler
///     something that is not the spec -- the reader then fails at column 2 of
///     whatever it got, which reads exactly like a bug in the reader.
///   * THE INITIALISERS MUST RUN FIRST, or that var is unbound.
///   * EVERYTHING IS ROOTED across allocations. `Str.of` on a 97 KB spec
///     allocates, the nursery is a copying collector, and a value held in a Java
///     local across it comes back holding the address it had before the flip
///     (`DECISIONS.md#a-vec-of-values-is-not-a-root`).
///   * THE SPEC GOES IN A LIST. `selfhost/main` takes an argv and dispatches on
///     its first element; handed the spec bare, `(first spec)` is the
///     one-character string `{`.
public final class Compiler {
    private final byte[] bytecode;

    private Compiler(byte[] bytecode) { this.bytecode = bytecode; }

    /// `dist/flintc.bytecode`, as bytes. Held rather than loaded: each `run`
    /// gets a fresh `Rt`, because the compiler's top-level initialisers re-run
    /// per call and nothing is shared between compilations.
    public static Compiler of(byte[] compilerBytecode) {
        if (compilerBytecode == null || compilerBytecode.length == 0) {
            throw new IllegalArgumentException("no compiler bytecode");
        }
        return new Compiler(compilerBytecode);
    }

    /// Run the compiler with `argv`, e.g. `run("jvm", specEdn)`. Answers exactly
    /// what the compiler answered: a base64 artifact, or a diagnostic starting
    /// `!missing` or `!refused`.
    public String run(String... argv) {
        Rt rt = new Rt(64L * 1024 * 1024, 2048L * 1024 * 1024);
        Img.Loaded img = Img.load(rt, bytecode);
        if (img == null) throw new IllegalStateException("not a flint image");
        rt.started = true;
        for (int fn : img.init) rt.call(rt.makeClosure(fn, new long[0]), new long[0]);
        int slot = -1;
        for (int i = 0; i < img.varNames.length; i++) {
            if ("flint.compiler.selfhost/main".equals(Str.text(rt, rt.consts[img.varNames[i]]))) slot = i;
        }
        if (slot < 0) throw new IllegalStateException("flint.compiler.selfhost/main is not in the var table");
        long compiler = rt.roots.shared.globals[slot];
        if (Val.isNil(compiler)) {
            throw new IllegalStateException("flint.compiler.selfhost/main is unbound after the initialisers");
        }
        int base = rt.mark();
        int ci = rt.push(compiler);
        int first = -1;
        for (String s : argv) {
            int i = rt.push(Str.of(rt, s));
            if (first < 0) first = i;
        }
        int li = rt.push(Seqs.fromRoots(rt, first, argv.length));
        long out = rt.runProgram(rt.r(ci), new long[]{ rt.r(li) });
        if (!Val.isNil(rt.thrown)) {
            String why = rt.isException(rt.thrown) && Str.isString(rt, rt.exMessage(rt.thrown))
                ? Str.text(rt, rt.exMessage(rt.thrown)) : rt.describe(rt.thrown);
            rt.popTo(base);
            throw new IllegalStateException("the compiler threw: " + why);
        }
        String s = Str.isString(rt, out) ? Str.text(rt, out)
                                        : "NOT A STRING: " + rt.describe(out);
        rt.popTo(base);
        return s;
    }

    /// Compile a spec to a JVM artifact and hand back an `Image` -- IN MEMORY,
    /// never a file. The mirror of the ESM SDK's compiler answering an `Image`.
    ///
    /// The spec may be already resolved -- `{:sources .. :order ..}`, which
    /// `bin/flint --emit-spec` writes -- because `build-image` skips resolution
    /// for one as of 2026-09-26. Before that no public route produced a spec any
    /// artifact target accepted, and this method could not exist; see below.
    public Image compileJvm(String specEdn) throws Exception {
        String out = run("jvm", specEdn, "", "");
        if (out.startsWith("!missing") || out.startsWith("!refused")) {
            throw new IllegalStateException(out.trim());
        }
        byte[] klass = java.util.Base64.getDecoder().decode(out.trim());
        // `CAFEBABE` OPENS EVERY CLASS FILE, and the compiler answers with a
        // string either way -- so a diagnostic decoded as bytes would be found out
        // by whoever loaded it, with no idea which step lied.
        if (klass.length < 4 || (klass[0] & 0xFF) != 0xCA || (klass[1] & 0xFF) != 0xFE
            || (klass[2] & 0xFF) != 0xBA || (klass[3] & 0xFF) != 0xBE) {
            throw new IllegalStateException(
                "the compiler did not answer with a class file (no `CAFEBABE`)");
        }
        return Image.of(klass);
    }

    /// ## What this method could not do until 2026-09-26
    ///
    /// It was written, removed as unreachable, and restored once the cause was
    /// fixed at the source. THERE WERE TWO SPEC SHAPES and no public route
    /// produced the one the artifact targets took.
    ///
    /// Measured 2026-09-26, same `Compiler`, two specs, two modes:
    ///
    ///     self.spec  mode spec -> RkxJTlRJTUcD...   (an image, 36 864 chars)
    ///     self.spec  mode jvm  -> !missing clojure.core hello flint.system ...
    ///     jvm.spec   mode spec -> RkxJTlRJTUcD...
    ///     jvm.spec   mode jvm  -> !missing clojure.core ok flint.system ...
    ///
    /// The MODE decides, not the spec. `compile-to-base64` -- what mode `spec`
    /// reaches -- takes a RESOLVED spec, `{:sources {ns {:src ..}} :order [..]}`,
    /// which is exactly what `bin/flint --emit-spec` writes. Every artifact target
    /// goes through `build-image`, which takes an UNRESOLVED one, `{:files ..
    /// :entry ..}`, and does the resolving itself. Each CLI builds that second
    /// shape internally and exposes it to nobody.
    ///
    /// So a JVM host can obtain the first shape and no artifact target accepts it.
    /// CLOSED IN `build-image`, not here: it now SKIPS resolution for a spec that
    /// already carries `:sources` and `:order`, and `spec-builtins` takes the
    /// builtin set from `:builtins` when there is no slot map to take it from --
    /// which `compile-project` already did for the same reason. Everything after
    /// the resolve step is unchanged, so all four targets still emit byte-identical
    /// artifacts from the CLIs' own unresolved specs; checked with `cmp` on wasm,
    /// clr, jvm and llvm.
    ///
    /// Sharing spec CONSTRUCTION is still its own change
    /// (`DECISIONS.md#one-dependency-walk`, `ROADMAP.md`'s item after `:to :llvm`).
    /// What this needed was narrower: not one spec builder, but one spec SHAPE
    /// every target accepts.
}
