import com.flint.*;

/// The same program interpreted and compiled, and the answers diffed.
///
/// The only thing worth asserting about a compiler is that it did not change
/// the answer. A backend that is fast and wrong is worse than no backend, and
/// "it ran" does not tell them apart.
public class AotTest {
    public static void main(String[] args) throws Exception {
        byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(args[0]));
        Img img = Img.read(bytes);

        Vm interp = new Vm(img);
        interp.ensureStarted();
        Object want = interp.runProgram(new Vm.Closure(img.entry, new Object[0]),
                                  new Object[]{ java.util.List.of() });

        Vm jit = new Vm(img);
        jit.aotEnabled = true;
        jit.ensureStarted();
        Object got = jit.runProgram(new Vm.Closure(img.entry, new Object[0]),
                              new Object[]{ java.util.List.of() });

        boolean same = Builtins.eq(want, got);
        System.out.println("  " + (same ? "ok  " : "FAIL")
            + " compiled and interpreted agree (" + jit.compiledCount + " arities compiled)");
        if (!same) {
            System.out.println("        interpreted " + Builtins.str(want));
            System.out.println("        compiled    " + Builtins.str(got));
            System.exit(1);
        }
        if (jit.compiledCount == 0) {
            if (!jit.canCompile()) {
                // A legitimate outcome, not a gap: this program uses green
                // threads, and a compiled arity's continuation is the Java
                // stack, so it cannot be compiled until `0013`'s chunking is
                // built here. The answers still had to AGREE, which they did.
                System.out.println("  ok   nothing compiled: green threads, so it interprets");
                return;
            }
            System.out.println("  FAIL nothing was compiled, so nothing was tested");
            System.exit(1);
        }

        // What it is FOR. Best-of, because a single timing on a JIT host is
        // mostly warm-up.
        double ti = best(interp, img, 9), tc = best(jit, img, 9);
        System.out.printf("       interpreted %.2f ms, compiled %.2f ms, %.2fx%n", ti, tc, ti / tc);
    }

    static double best(Vm vm, Img img, int runs) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < runs; i++) {
            long t0 = System.nanoTime();
            vm.runProgram(new Vm.Closure(img.entry, new Object[0]), new Object[]{ java.util.List.of() });
            best = Math.min(best, (System.nanoTime() - t0) / 1e6);
        }
        return best;
    }
}
