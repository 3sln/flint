using Flint;

/// Run an image and print what it returned.
///
/// The same shape as the JVM's runner: one source, compiled once, run on every
/// runtime, answers diffed. `doc/decisions/0010` is explicit that the bytecode
/// makes a port cheap and does nothing to make two ports AGREE.
public static class Program {
    public static int Main(string[] args) {
        if (args.Length >= 2 && args[0] == "--threads") return Threads(args[1]);
        if (args.Length >= 2 && args[0] == "--aot") return Aot(args[1]);
        if (args.Length >= 2 && args[0] == "--flags") return Flags(args[1]);
        if (args.Length >= 3 && args[0] == "--selfhost") return SelfHost(args[1], args[2]);
        var vm = new Vm(Img.Read(File.ReadAllBytes(args[0])));
        vm.EnsureStarted();
        object outv = vm.RunProgram(new Vm.Closure(vm.Img.Entry, Array.Empty<object>()),
                              new object[] { new Vec() });
        Console.WriteLine(Builtins.Str(outv));
        return 0;
    }

    /// What the compiler decided, and whether this runtime acted on it.
    ///
    /// `:optimize [perf]` has to mean the same thing on all three runtimes and
    /// cannot be carried the same way on any two: on wasm it changes the
    /// artifact, here the IL is emitted at load time from the same bytecode. So
    /// the image carries the decision and the Vm reads it -- and this prints
    /// both halves so the claim is checked rather than asserted in a comment.
    private static int Flags(string path) {
        var img = Img.Read(File.ReadAllBytes(path));
        var vm = new Vm(img);
        Console.WriteLine($"flags={img.Flags} aot={(vm.AotEnabled ? "true" : "false")}");
        return 0;
    }

    /// Run the flint COMPILER on .NET, and compare what it emits with what the
    /// native compiler emits from the same input -- byte for byte.
    ///
    /// This is the difference between "runs programs" and "self-hosts". The
    /// compiler is the largest flint program there is, so it reaches builtins a
    /// small program never does; and an image that DIFFERS is a compiler that
    /// differs, whose difference will surface in a program no test here runs.
    private static int SelfHost(string specPath, string refPath) {
        var vm = new Vm(Img.Read(File.ReadAllBytes("dist/flintc.bytecode")));
        vm.EnsureStarted();

        // `flint.selfhost/main` is a VAR, not a named entry in the function
        // table: the initialisers put its closure in a slot, which is why
        // EnsureStarted has to have run.
        int slot = -1;
        for (int i = 0; i < vm.Img.VarNames.Length; i++) {
            if (vm.Img.VarNames[i] == "flint.selfhost/main") slot = i;
        }
        if (slot < 0) { Console.WriteLine("  FAIL flint.selfhost/main is not in the var table"); return 1; }
        object compiler = vm.GetVarPublic(slot);
        if (compiler == null) { Console.WriteLine("  FAIL flint.selfhost/main is unbound"); return 1; }

        // ONE argument: anything that is not "wasm" or "project" is the SPEC.
        object outv = vm.Call(compiler, new object[] { new Vec(new object[] { File.ReadAllText(specPath) }) });
        string first = Builtins.Str(outv).Split('\n')[0].Trim();
        if (first.Length < 100) {
            Console.WriteLine("  FAIL the compiler did not emit an image: "
                              + first.Substring(0, Math.Min(200, first.Length)));
            return 1;
        }
        Console.WriteLine($"  ok   the flint compiler ran on the CLR and emitted {first.Length} base64 chars");

        byte[] got = Convert.FromBase64String(first);
        byte[] want = Convert.FromBase64String(File.ReadAllText(refPath).Split('\n')[0].Trim());
        if (got.Length == want.Length && got.AsSpan().SequenceEqual(want)) {
            Console.WriteLine($"  ok   byte for byte the image the native compiler emits ({got.Length} bytes)");
            return 0;
        }
        int at = -1;
        for (int i = 0; i < Math.Min(got.Length, want.Length); i++) if (got[i] != want[i]) { at = i; break; }
        Console.WriteLine($"  FAIL the image differs from the native compiler's: {got.Length} bytes"
                          + $" against {want.Length}"
                          + (at < 0 ? ", one a prefix of the other" : $", first differing byte at {at}"));
        return 1;
    }

    /// AOT: the same program interpreted and compiled, and the answers diffed.
    ///
    /// The only thing worth asserting about a compiler is that it did not
    /// change the answer. A backend that is fast and wrong is worse than no
    /// backend, and "it ran" does not tell them apart.
    private static int Aot(string path) {
        var img = Img.Read(File.ReadAllBytes(path));

        var interp = new Vm(img);
        interp.EnsureStarted();
        object want = interp.RunProgram(new Vm.Closure(img.Entry, Array.Empty<object>()),
                                  new object[] { new Vec() });

        var jit = new Vm(img) { AotEnabled = true };
        jit.EnsureStarted();
        object got = jit.RunProgram(new Vm.Closure(img.Entry, Array.Empty<object>()),
                              new object[] { new Vec() });

        bool same = Builtins.Eq(want, got);
        Console.WriteLine("  " + (same ? "ok  " : "FAIL")
            + " compiled and interpreted agree (" + jit.CompiledCount + " arities compiled"
            + (System.Environment.GetEnvironmentVariable("FLINT_AOT_HISTO") == "1"
               ? ", " + Flint.Aot.Fused + " compares fused, " + Flint.Aot.Runs + " int runs" : "")
            + ")");
        if (System.Environment.GetEnvironmentVariable("FLINT_AOT_HISTO") == "1") {
            var h = new System.Collections.Generic.List<System.Collections.Generic.KeyValuePair<int,int>>(Flint.Aot.Histo);
            h.Sort((a, b) => b.Value.CompareTo(a.Value));
            foreach (var e in h) System.Console.WriteLine($"       0x{e.Key:X2} {e.Value}");
        }
        if (!same) {
            Console.WriteLine("        interpreted " + Builtins.Str(want));
            Console.WriteLine("        compiled    " + Builtins.Str(got));
            return 1;
        }
        if (jit.CompiledCount == 0) {
            if (!jit.CanCompile) {
                // Legitimate, not a gap: green threads mean a compiled arity's
                // continuation would be the CLR stack. The answers still had to
                // agree, and did.
                Console.WriteLine("  ok   nothing compiled: green threads, so it interprets");
                return 0;
            }
            Console.WriteLine("  FAIL nothing was compiled, so nothing was tested");
            return 1;
        }

        // What it is FOR. Best-of, because a single timing on a JIT host is
        // mostly warm-up.
        double Best(Vm vm, int runs) {
            double best = double.MaxValue;
            for (int i = 0; i < runs; i++) {
                var sw = System.Diagnostics.Stopwatch.StartNew();
                vm.RunProgram(new Vm.Closure(img.Entry, Array.Empty<object>()), new object[] { new Vec() });
                sw.Stop();
                best = Math.Min(best, sw.Elapsed.TotalMilliseconds);
            }
            return best;
        }
        double ti = Best(interp, 7), tc = Best(jit, 7);
        Console.WriteLine($"       interpreted {ti:F2} ms, compiled {tc:F2} ms, {ti / tc:F2}x");
        return 0;
    }

    /// Several threads running ONE flint program.
    ///
    /// There is no safepoint here and no collector of ours to stop, because the
    /// CLR's owns lifetime. What has to be right is flint's own shared state --
    /// the var slots and atoms.
    private static int Threads(string path) {
        int failures = 0;
        void Ok(bool c, string what) {
            Console.WriteLine("  " + (c ? "ok  " : "FAIL") + " " + what);
            if (!c) failures++;
        }

        var img = Img.Read(File.ReadAllBytes(path));
        var vm = new Vm(img);
        vm.EnsureStarted();

        int fnTally = -1, fnWork = -1;
        for (int i = 0; i < img.Fns.Length; i++) {
            string n = img.Fns[i].Name;
            if (n == null) continue;
            if (n.EndsWith("tally")) fnTally = i;
            if (n.EndsWith("work")) fnWork = i;
        }
        // Both must be REACHABLE from the entry, or tree shaking removes them
        // and there is nothing to call (`doc/decisions/0002`).
        if (fnTally < 0 || fnWork < 0) {
            Console.WriteLine($"  FAIL the program's functions are findable (tally={fnTally} work={fnWork})");
            return 1;
        }
        Ok(true, "the program's functions are findable");

        const int Threads = 8, Per = 200;
        object want = null;
        bool sameEvery = true;
        Parallel.For(0, Threads, _ => {
            object got = vm.Call(new Vm.Closure(fnWork, Array.Empty<object>()), new object[] { 100L });
            lock (img) {
                if (want == null) want = got;
                else if (!Builtins.Eq(want, got)) sameEvery = false;
            }
        });
        Ok(sameEvery, $"eight threads computing the same thing agree ({Builtins.Str(want)})");

        // `swap!` is a CAS retry loop in `lib/clojure/core.cljc`, so these
        // increments must all land. A lost update shows up as a smaller number
        // and nothing else, which is why this counts rather than samples.
        Parallel.For(0, Threads, _ => {
            for (int i = 0; i < Per; i++)
                vm.Call(new Vm.Closure(fnTally, Array.Empty<object>()), Array.Empty<object>());
        });
        long got2 = (long) vm.Call(new Vm.Closure(fnTally, Array.Empty<object>()), Array.Empty<object>());
        long want2 = (long) Threads * Per + 1;
        Ok(got2 == want2,
           $"{Threads} threads x {Per} increments lose none (got {got2}, want {want2})");

        Console.WriteLine(failures == 0 ? "\nall checks passed" : "\nFAILED");
        return failures == 0 ? 0 : 1;
    }
}
