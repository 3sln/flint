namespace Flint.Rt;

using System;

/// THE FLINT COMPILER, RUNNING ON THIS RUNTIME.
///
/// The CLR's mirror of `com.flint.Compiler` on the JVM and the ESM SDK's
/// `Compiler`. All three run the SAME compiler -- it is a flint program -- and
/// self-hosting on this runtime is gated by `FLINT_SELFHOST=1 ./bin/conform-hosts`.
///
/// Promoted from `runtimes/clr/conform/Program.cs`'s `RtSelfHost`, which is where
/// this sequence was worked out and where the four things a second implementation
/// gets wrong are written down:
///
///   * `flint.selfhost/main` is a VAR, found through the image's var table.
///     `img.entry` is a different function, and calling it hands the compiler
///     something that is not the spec.
///   * THE INITIALISERS MUST RUN FIRST, or that var is unbound.
///   * EVERYTHING IS ROOTED across allocations: the nursery copies, so a value
///     held in a host local comes back with a pre-flip address
///     (`DECISIONS.md#a-vec-of-values-is-not-a-root`).
///   * THE SPEC GOES IN A LIST, because `main` takes an argv and dispatches on
///     its first element.
///
/// ## It takes a spec and does not build one
///
/// For `com.flint.Compiler`'s reason: `cli/src/main.rs`, `bin/flint`,
/// `project.cljc` and the ESM SDK each build a compile spec, which is four
/// implementations of one fact and a hazard `DECISIONS.md#one-dependency-walk`
/// records by name. A fifth would make it worse.
///
/// A spec that is ALREADY RESOLVED works -- `{:sources .. :order ..}`, what
/// `bin/flint --emit-spec` writes -- because `build-image` skips resolution for
/// one. Before that no public route produced a spec any artifact target accepted.
public sealed class Compiler {
    readonly byte[] bytecode;

    Compiler(byte[] bytecode) { this.bytecode = bytecode; }

    /// `dist/flintc.bytecode`, as bytes. Held rather than loaded: each `Run` gets
    /// a fresh `Rt`, because the compiler's top-level initialisers re-run per call
    /// and nothing is shared between compilations.
    public static Compiler Of(byte[] compilerBytecode) {
        if (compilerBytecode == null || compilerBytecode.Length == 0) {
            throw new ArgumentException("no compiler bytecode");
        }
        return new Compiler(compilerBytecode);
    }

    /// Run the compiler with `argv`, e.g. `Run("clr", spec)`. Answers exactly what
    /// the compiler answered: a base64 artifact, or a diagnostic opening
    /// `!missing` or `!refused`.
    public string Run(params string[] argv) {
        var rt = new Rt(64L * 1024 * 1024, 2048L * 1024 * 1024);
        var img = Img.Load(rt, bytecode);
        if (img == null) throw new InvalidOperationException("not a flint image");
        rt.started = true;
        foreach (int fn in img.init)
            rt.Call(rt.MakeClosure(fn, Array.Empty<long>()), Array.Empty<long>());
        int slot = -1;
        for (int i = 0; i < img.varNames.Length; i++)
            if (Str.Text(rt, rt.consts[img.varNames[i]]) == "flint.selfhost/main") slot = i;
        if (slot < 0) {
            throw new InvalidOperationException("flint.selfhost/main is not in the var table");
        }
        long compiler = rt.roots.shared.Globals[slot];
        if (Val.IsNil(compiler)) {
            throw new InvalidOperationException(
                "flint.selfhost/main is unbound after the initialisers");
        }
        int bas = rt.Mark();
        int ci = rt.Push(compiler);
        int first = -1;
        foreach (string s in argv) {
            int i = rt.Push(Str.Of(rt, s));
            if (first < 0) first = i;
        }
        int li = rt.Push(Seqs.FromRoots(rt, first, argv.Length));
        long outv = rt.RunProgram(rt.R(ci), new long[]{ rt.R(li) });
        if (!Val.IsNil(rt.thrown)) {
            string why = rt.Describe(rt.thrown);
            rt.PopTo(bas);
            throw new InvalidOperationException("the compiler threw: " + why);
        }
        string text = Str.IsString(rt, outv) ? Str.Text(rt, outv)
                    : "NOT A STRING: " + rt.Describe(outv);
        rt.PopTo(bas);
        return text;
    }

    /// Compile a spec to a CLR artifact and hand back an `Image` -- IN MEMORY,
    /// never a file. The mirror of `compileJvm` on the JVM.
    ///
    /// THE SPEC MUST CARRY `:slots`, which an already-resolved spec does not, and
    /// `:to :clr` REFUSES one that does not rather than emitting an assembly
    /// nothing can load. That restriction is this target's, not the mechanism's:
    /// the JVM takes a resolved spec and is verified byte-identical to the wasm
    /// compiler on one. Measured: a resolved spec produced a 29 184-byte assembly
    /// -- the same length as the good one, 83 bytes different, `.text` 36 bytes
    /// larger -- that `Assembly.Load` rejected with `BadImageFormatException: Bad
    /// IL format`.
    ///
    /// THE CAUSE IS NOT ESTABLISHED; `src/flint/selfhost.cljc`'s `compile-to-clr`
    /// lists what has been ruled out. Not a port divergence (the wasm compiler
    /// produced the same bytes), not the builtin set (the same 226 names, quoted
    /// differently), and not checks (`:checks true` and `false` give identical
    /// assemblies). The refusal is right whatever the cause.
    public Image CompileClr(string specEdn) {
        string outv = Run("clr", specEdn);
        if (outv.StartsWith("!missing") || outv.StartsWith("!refused")) {
            throw new InvalidOperationException(outv.Trim());
        }
        // `Image.Of` sniffs for `MZ` and refuses anything else by name, which is
        // what keeps a diagnostic from being handed to `Assembly.Load` and failing
        // deep in the loader.
        return Image.Of(Convert.FromBase64String(outv.Trim()));
    }
}
