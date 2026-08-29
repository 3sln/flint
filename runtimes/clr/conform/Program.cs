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
        if (args.Length >= 1 && args[0] == "--rt-foundation") return RtFoundation();
        if (args.Length >= 3 && args[0] == "--selfhost") return SelfHost(args[1], args[2]);
        var vm = new Vm(Img.Read(File.ReadAllBytes(args[0])));
        vm.EnsureStarted();
        object outv = vm.RunProgram(new Vm.Closure(vm.Img.Entry, Array.Empty<object>()),
                              new object[] { new Vec() });
        Console.WriteLine(Builtins.Str(outv));
        return 0;
    }

    /// The ported runtime's foundation: values, memory and object layout.
    ///
    /// The SAME assertions as `runtimes/jvm/test/RtFoundation.java`, in the same
    /// order, because the two are meant to be verbatim mirrors and the only way
    /// to know that is to check rather than to intend it.
    ///
    /// The number at the end is the cost of the REPRESENTATION, with no
    /// dispatch -- not an end-to-end speedup and not to be quoted as one. What
    /// it settles is that boxing is no longer the ceiling.
    private static int RtFoundation() {
        foreach (long off in new long[]{8, 16, 0x1000, 0xFFFF_FFFFL, 0x1_0000_0000L,
                                        0x0000_FFFF_FFFF_FFF8L}) {
            long v = Flint.Rt.Val.Heap(off);
            if (!Flint.Rt.Val.IsHeap(v) || Flint.Rt.Val.AsHeap(v) != off) {
                Console.WriteLine($"  FAIL heap round trip at {off:x}");
                return 1;
            }
        }
        foreach (long n in new long[]{0, 1, -1, 1L << 40, -(1L << 40)}) {
            if (Flint.Rt.Val.AsFixnum(Flint.Rt.Val.Fixnum(n)) != n) {
                Console.WriteLine($"  FAIL fixnum round trip at {n}");
                return 1;
            }
        }
        Console.WriteLine("  ok   values round-trip across 48 bits");

        using var sp = new Flint.Rt.Space(64L * 1024 * 1024);
        long addr = sp.Take(1024);
        Flint.Rt.Obj.WriteHeader(sp, addr, Flint.Rt.Obj.TyCons, 4);
        if (Flint.Rt.Obj.Ty(sp, addr) != Flint.Rt.Obj.TyCons || Flint.Rt.Obj.Len(sp, addr) != 4) {
            Console.WriteLine("  FAIL header round trip"); return 1;
        }
        Flint.Rt.Obj.SetSlotRaw(sp, addr, 0, Flint.Rt.Val.Fixnum(42));
        if (Flint.Rt.Val.AsFixnum(Flint.Rt.Obj.Slot(sp, addr, 0)) != 42) {
            Console.WriteLine("  FAIL slot round trip"); return 1;
        }
        Flint.Rt.Obj.SetMarked(sp, addr, true);
        if (!Flint.Rt.Obj.Marked(sp, addr) || Flint.Rt.Obj.Ty(sp, addr) != Flint.Rt.Obj.TyCons) {
            Console.WriteLine("  FAIL the mark bit disturbed the type"); return 1;
        }
        long far = 0x0000_FF00_1234_5678L;
        Flint.Rt.Obj.SetForward(sp, addr, far);
        if (Flint.Rt.Obj.ForwardTarget(sp, addr) != far) {
            Console.WriteLine("  FAIL a 48-bit forward did not survive the header"); return 1;
        }
        Console.WriteLine("  ok   objects, mark bits and 48-bit forwarding");

        GcStress();
        Interpreter();

        long stack = sp.Take(1024);
        long best = long.MaxValue;
        var sw = new System.Diagnostics.Stopwatch();
        for (int rep = 0; rep < 7; rep++) {
            sp.WriteU64(stack, Flint.Rt.Val.Fixnum(0));
            sp.WriteU64(stack + 8, Flint.Rt.Val.Fixnum(0));
            sw.Restart();
            for (int k = 0; k < 3_000_000; k++) {
                long i = Flint.Rt.Val.AsFixnum(sp.ReadU64(stack));
                long acc = Flint.Rt.Val.AsFixnum(sp.ReadU64(stack + 8));
                sp.WriteU64(stack, Flint.Rt.Val.Fixnum(i + 1));
                sp.WriteU64(stack + 8, Flint.Rt.Val.Fixnum(acc + i));
            }
            sw.Stop();
            best = System.Math.Min(best, sw.Elapsed.Ticks * 100);
        }
        double ns = best / 3_000_000.0;
        Console.WriteLine($"    3,000,000 iterations, best of 7: {ns:F2} ns/iteration");
        Console.WriteLine($"    against 22 ns boxed on the current port -- {22.0 / ns:F0}x");
        return 0;
    }

    /// The interpreter, on hand-assembled bytecode. The SAME three checks as
    /// `runtimes/jvm/test/RtFoundation.java`, in the same order.
    ///
    /// Hand-assembled rather than loaded from an image, so this tests the
    /// dispatch loop and nothing else: the image loader and the builtins are
    /// not ported yet, and a failure here would otherwise have three possible
    /// causes instead of one.
    private static void Interpreter() {
        using var rt = new Flint.Rt.Rt(256 * 1024, 16L * 1024 * 1024);

        var a = new Asm();
        a.Op(Flint.Rt.Op.Int).I16(0).Op(Flint.Rt.Op.SetLocal).U8(1);
        a.Op(Flint.Rt.Op.Int).I16(0).Op(Flint.Rt.Op.SetLocal).U8(2);
        int top = a.At();
        a.Op(Flint.Rt.Op.Local).U8(1).Op(Flint.Rt.Op.Local).U8(0).Op(Flint.Rt.Op.LtInt);
        int exit = a.Op(Flint.Rt.Op.JumpIfFalse).Hole();
        a.Op(Flint.Rt.Op.Local).U8(2).Op(Flint.Rt.Op.Local).U8(1).Op(Flint.Rt.Op.AddInt).Op(Flint.Rt.Op.SetLocal).U8(2);
        a.Op(Flint.Rt.Op.Local).U8(1).Op(Flint.Rt.Op.Int).I16(1).Op(Flint.Rt.Op.AddInt).Op(Flint.Rt.Op.SetLocal).U8(1);
        a.JumpTo(top);
        a.Patch(exit);
        a.Op(Flint.Rt.Op.Local).U8(2).Op(Flint.Rt.Op.Return);

        rt.code = a.Done();
        rt.fns = new[]{ new Flint.Rt.Rt.FnDef(
            new[]{ new Flint.Rt.Rt.Arity(1, false, 3, 0, rt.code.Length) }, 0) };
        long f = rt.MakeClosure(0, System.Array.Empty<long>());
        long got = rt.Call(f, new[]{ Flint.Rt.Val.Fixnum(1000) });
        long want = 1000L * 999 / 2;
        if (Flint.Rt.Val.AsFixnum(got) != want)
            throw new System.Exception($"loop gave {Flint.Rt.Val.AsFixnum(got)} want {want}");
        Console.WriteLine($"  ok   the interpreter runs a counting loop ({Flint.Rt.Val.AsFixnum(got):N0} in {rt.steps:N0} steps)");

        var b = new Asm();
        b.Op(Flint.Rt.Op.Closure).U16(0).U8(0);
        b.Op(Flint.Rt.Op.Local).U8(0);
        b.Op(Flint.Rt.Op.Call).U8(1);
        b.Op(Flint.Rt.Op.Int).I16(1).Op(Flint.Rt.Op.AddInt).Op(Flint.Rt.Op.Return);
        byte[] second = b.Done();
        int off = rt.code.Length;
        var both = new byte[off + second.Length];
        System.Array.Copy(rt.code, both, off);
        System.Array.Copy(second, 0, both, off, second.Length);
        rt.code = both;
        rt.fns = new[]{
            new Flint.Rt.Rt.FnDef(new[]{ new Flint.Rt.Rt.Arity(1, false, 3, 0, off) }, 0),
            new Flint.Rt.Rt.FnDef(new[]{ new Flint.Rt.Rt.Arity(1, false, 2, off, second.Length) }, 0),
        };
        long g = rt.MakeClosure(1, System.Array.Empty<long>());
        long got2 = rt.Call(g, new[]{ Flint.Rt.Val.Fixnum(100) });
        if (Flint.Rt.Val.AsFixnum(got2) != 100L * 99 / 2 + 1)
            throw new System.Exception($"nested call gave {Flint.Rt.Val.AsFixnum(got2)}");
        Console.WriteLine("  ok     ... and a call and return across frames");

        // The comparison is EXPLICIT: 0 is truthy in Clojure, so branching on
        // `n` itself would never terminate.
        var c = new Asm();
        c.Op(Flint.Rt.Op.Local).U8(0).Op(Flint.Rt.Op.Int).I16(0).Op(Flint.Rt.Op.GtInt);
        int done = c.Op(Flint.Rt.Op.JumpIfFalse).Hole();
        c.Op(Flint.Rt.Op.Self);
        c.Op(Flint.Rt.Op.Local).U8(0).Op(Flint.Rt.Op.Int).I16(1).Op(Flint.Rt.Op.SubInt);
        c.Op(Flint.Rt.Op.TailCall).U8(1);
        c.Patch(done);
        c.Op(Flint.Rt.Op.Local).U8(0).Op(Flint.Rt.Op.Return);
        rt.code = c.Done();
        rt.fns = new[]{ new Flint.Rt.Rt.FnDef(
            new[]{ new Flint.Rt.Rt.Arity(1, false, 2, 0, rt.code.Length) }, 0) };
        long h = rt.MakeClosure(0, System.Array.Empty<long>());
        int before = rt.frames.Count;
        long got3 = rt.Call(h, new[]{ Flint.Rt.Val.Fixnum(200_000) });
        if (Flint.Rt.Val.AsFixnum(got3) != 0)
            throw new System.Exception($"tail recursion gave {Flint.Rt.Val.AsFixnum(got3)}");
        if (rt.frames.Count != before)
            throw new System.Exception($"frames leaked: {before} -> {rt.frames.Count}");
        Console.WriteLine("  ok     ... and 200,000 tail calls in constant frame space");
    }

    /// A tiny assembler, the same shape as the Rust tests'.
    private sealed class Asm {
        private byte[] b = new byte[64];
        private int n;
        public int At() => n;
        private void Put(int x) {
            if (n == b.Length) System.Array.Resize(ref b, n * 2);
            b[n++] = (byte) x;
        }
        public Asm Op(int o) { Put(o); return this; }
        public Asm U8(int v) { Put(v); return this; }
        public Asm U16(int v) { Put(v & 0xFF); Put((v >> 8) & 0xFF); return this; }
        public Asm I16(int v) => U16(v & 0xFFFF);
        public int Hole() { int h = n; Put(0); Put(0); return h; }
        public void Patch(int h) { int o = n - (h + 2); b[h] = (byte)(o & 0xFF); b[h + 1] = (byte)((o >> 8) & 0xFF); }
        public void JumpTo(int target) { Put(Flint.Rt.Op.Jump); int o = target - (n + 2); Put(o & 0xFF); Put((o >> 8) & 0xFF); }
        public byte[] Done() { var outb = new byte[n]; System.Array.Copy(b, outb, n); return outb; }
    }

    /// The collector, under pressure, with the invariant asserted rather than
    /// hoped for. The SAME test as `runtimes/jvm/test/RtFoundation.java`.
    ///
    /// Builds a linked list far larger than the nursery, so it is collected
    /// many times over and every survivor is copied, promoted, and pointed at
    /// from the old generation. Then walks it. A collector that loses ONE
    /// object, or forwards one pointer wrongly, produces a wrong sum -- and the
    /// walk is what turns "it did not crash" into a result.
    private static void GcStress() {
        using var gc = new Flint.Rt.Gc(256 * 1024, 64L * 1024 * 1024);
        var roots = new Flint.Rt.Roots();
        const int N = 200_000;

        // Held only through the shadow stack: the CLR local goes stale at the
        // first collection, which is the whole point of `Push`/`R`.
        int head = roots.Push(Flint.Rt.Val.Nil);
        for (int i = 0; i < N; i++) {
            long cell = gc.Alloc(roots, Flint.Rt.Obj.TyCons, 4);
            if (cell == 0) throw new System.Exception("out of heap at " + i);
            gc.SetSlot(cell, 0, Flint.Rt.Val.Fixnum(i), roots);
            gc.SetSlot(cell, 1, roots.R(head), roots);
            roots.SetR(head, Flint.Rt.Val.Heap(cell));
        }

        long want = (long) N * (N - 1) / 2;
        long sum = 0;
        int seen = 0;
        long cur = roots.R(head);
        while (!Flint.Rt.Val.IsNil(cur)) {
            long a = Flint.Rt.Val.AsHeap(cur);
            sum += Flint.Rt.Val.AsFixnum(Flint.Rt.Obj.Slot(gc.sp, a, 0));
            seen++;
            cur = Flint.Rt.Obj.Slot(gc.sp, a, 1);
        }
        if (seen != N) throw new System.Exception($"walked {seen} of {N}");
        if (sum != want) throw new System.Exception($"sum {sum} want {want}");
        Console.WriteLine($"  ok   {N:N0} objects survive {gc.minors:N0} minor and {gc.majors:N0} major collections intact");

        // Garbage really is reclaimed. Without this the assertion above passes
        // for the wrong reason: a collector that keeps everything loses nothing.
        long before = gc.HeapUsed();
        roots.SetR(head, Flint.Rt.Val.Nil);
        gc.Major(roots);
        if (gc.HeapUsed() >= before)
            throw new System.Exception($"nothing was reclaimed: {before} -> {gc.HeapUsed()}");
        Console.WriteLine($"  ok     ... and dropping them reclaims {before - gc.HeapUsed():N0} bytes");
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
