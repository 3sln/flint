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
        // `swap!` is a CAS retry loop in `lib/clojure/core.cljc`, so 8 x 200
        // increments must be exactly 1600 and not one fewer. A lost update
        // shows up as a smaller number and nothing else -- no crash, no
        // exception -- which is why this counts rather than samples.
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
        long expected = (long) THREADS * PER + 1;
        long got = (Long) total;
        ok(got == expected, THREADS + " threads x " + PER
           + " increments lose none (got " + got + ", want " + expected + ")");

        System.out.println(failures == 0 ? "\nall checks passed" : "\nFAILED");
        if (failures != 0) System.exit(1);
    }
}
