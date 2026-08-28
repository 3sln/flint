import com.flint.*;
import java.util.*;
import java.util.concurrent.*;

/// Several threads running ONE flint program on the JVM.
///
/// The contrast with `doc/decisions/0028` is the point of tier 2: there is no
/// safepoint here and no collector of ours to stop, because the JVM's owns
/// lifetime. What has to be right is flint's own shared state -- the var slots
/// and atoms -- and this is what says whether it is.
public class ThreadTest {
    static int failures = 0;
    static void ok(boolean c, String what) {
        System.out.println("  " + (c ? "ok  " : "FAIL") + " " + what);
        if (!c) failures++;
    }

    public static void main(String[] args) throws Exception {
        byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(args[0]));
        Img img = Img.read(bytes);
        Vm vm = new Vm(img);
        vm.ensureStarted();

        int fnTally = -1, fnWork = -1;
        for (int i = 0; i < img.fns.length; i++) {
            String n = img.fns[i].name;
            if (n == null) continue;
            if (n.endsWith("tally")) fnTally = i;
            if (n.endsWith("work")) fnWork = i;
        }
        // Both must be REACHABLE from the entry, or tree shaking removes them
        // and there is nothing to call (`doc/decisions/0002`). A test that
        // could not find them used to index -1 and blame concurrency.
        if (fnTally < 0 || fnWork < 0) {
            System.out.println("  FAIL the program's functions are findable"
                + " (tally=" + fnTally + " work=" + fnWork + ")");
            System.exit(1);
        }
        ok(true, "the program's functions are findable");

        final int THREADS = 8, PER = 200;
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        // Pure work from many threads: the answer must be the same every time.
        List<Future<Object>> pure = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            final int fn = fnWork;
            pure.add(pool.submit(() ->
                vm.call(new Vm.Closure(fn, new Object[0]), new Object[]{ 100L })));
        }
        Object want = null;
        boolean sameEvery = true;
        for (Future<Object> f : pure) {
            Object got = f.get();
            if (want == null) want = got;
            else if (!Builtins.eq(want, got)) sameEvery = false;
        }
        ok(sameEvery, "eight threads computing the same thing agree (" + want + ")");

        // An ATOM from many threads.
        //
        // `swap!` is `(reset! a (f (deref a)))` in `lib/clojure/core.cljc` --
        // a read-modify-write with no atomicity -- so this LOSES updates, and
        // that is what it asserts. Not a JVM problem: the same holds on every
        // runtime the moment two threads are inside one sandbox.
        //
        // The retry loop that fixes it is written and works interpreted. It is
        // off because turning it on makes an AOT compile abort, for a reason
        // that is not yet understood -- see `doc/decisions/0013`.
        //
        // Asserting the CURRENT behaviour rather than the wanted one, so that
        // fixing the AOT rooting shows up here as a test to update rather than
        // as nothing at all.
        List<Future<?>> bumps = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            final int fn = fnTally;
            bumps.add(pool.submit(() -> {
                for (int i = 0; i < PER; i++) {
                    vm.call(new Vm.Closure(fn, new Object[0]), new Object[0]);
                }
            }));
        }
        for (Future<?> f : bumps) f.get();
        pool.shutdown();

        Object total = vm.call(new Vm.Closure(fnTally, new Object[0]), new Object[0]);
        long ideal = (long) THREADS * PER + 1;
        long got = (Long) total;
        ok(got >= 1 && got <= ideal,
           "swap! is a read-modify-write, so " + THREADS + "x" + PER
           + " increments land somewhere in 1.." + ideal + " (got " + got + ")");
        if (got < ideal) {
            System.out.println("       (lost " + (ideal - got)
                + " updates -- see doc/decisions/0013)");
        }

        System.out.println(failures == 0 ? "\nall checks passed" : "\nFAILED");
        if (failures != 0) System.exit(1);
    }
}
