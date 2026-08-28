using Flint;

/// Run an image and print what it returned.
///
/// The same shape as the JVM's runner: one source, compiled once, run on every
/// runtime, answers diffed. `doc/decisions/0010` is explicit that the bytecode
/// makes a port cheap and does nothing to make two ports AGREE.
public static class Program {
    public static int Main(string[] args) {
        if (args.Length >= 2 && args[0] == "--threads") return Threads(args[1]);
        var vm = new Vm(Img.Read(File.ReadAllBytes(args[0])));
        vm.EnsureStarted();
        object outv = vm.Call(new Vm.Closure(vm.Img.Entry, Array.Empty<object>()),
                              new object[] { new Vec() });
        Console.WriteLine(Builtins.Str(outv));
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

        // `swap!` is `(reset! a (f (deref a)))` -- a read-modify-write with no
        // atomicity -- so this LOSES updates, and that is what it asserts.
        // Not a CLR problem: the same holds on every runtime the moment two
        // threads are inside one sandbox. See `doc/decisions/0013`.
        Parallel.For(0, Threads, _ => {
            for (int i = 0; i < Per; i++)
                vm.Call(new Vm.Closure(fnTally, Array.Empty<object>()), Array.Empty<object>());
        });
        long got2 = (long) vm.Call(new Vm.Closure(fnTally, Array.Empty<object>()), Array.Empty<object>());
        long ideal = (long) Threads * Per + 1;
        Ok(got2 >= 1 && got2 <= ideal,
           $"swap! is a read-modify-write, so {Threads}x{Per} increments land somewhere in 1..{ideal} (got {got2})");

        Console.WriteLine(failures == 0 ? "\nall checks passed" : "\nFAILED");
        return failures == 0 ? 0 : 1;
    }
}
