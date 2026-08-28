import com.flint.*;
import java.nio.file.*;
import java.util.List;

/// Run the flint COMPILER on the JVM.
///
/// This is the difference between "runs programs" and "self-hosts": the
/// compiler is the largest flint program there is, so it reaches builtins a
/// small program never does, and it is the only thing that says whether a port
/// carries the whole language rather than the part the tests happened to use.
public class SelfHost {
    public static void main(String[] args) throws Exception {
        // A thread with a big stack. Each flint call is a JVM frame here, and
        // the compiler is deeply recursive -- `-Xss` does not apply to the
        // main thread on every JVM, but a thread's own stackSize always does.
        Thread t = new Thread(null, () -> { try { run(args); } catch (StackOverflowError e) {
            // The DEPTH is the diagnosis. "Deep but finite" and "unbounded"
            // look identical from a stack trace and want opposite fixes, so
            // count the frames rather than reading them.
            System.out.println("  StackOverflowError at depth " + e.getStackTrace().length
                               + " (run -XX:MaxJavaStackTraceDepth=0 for the true depth)");
            System.exit(1);
        } catch (Throwable e) {
            e.printStackTrace(); System.exit(1); } }, "flint", 2L * 1024 * 1024 * 1024);
        t.start();
        t.join();
    }

    static void run(String[] args) throws Exception {
        byte[] image = Files.readAllBytes(Path.of("dist/flintc.bytecode"));
        String spec = Files.readString(Path.of(args[0]));

        Vm vm = new Vm(Img.read(image));
        vm.aotEnabled = args.length > 1 && args[1].equals("--aot");
        System.out.println("  .. running initialisers" + (vm.aotEnabled ? " (aot)" : ""));
        vm.ensureStarted();
        System.out.println("  .. initialisers done");
        // `flint.selfhost/main` is a VAR, not a named entry in the function
        // table -- functions there are named `main`, `-main` and so on, while
        // the qualified name lives in the var table. The initialisers put the
        // closure in its slot, which is why `ensureStarted` has to have run.
        int slot = -1;
        for (int i = 0; i < vm.img.varNames.length; i++) {
            if ("flint.selfhost/main".equals(vm.img.varNames[i])) slot = i;
        }
        if (slot < 0) {
            System.out.println("  FAIL flint.selfhost/main is not in the var table");
            System.exit(1);
        }
        Object compiler = vm.vars.get(slot);
        if (compiler == null) {
            System.out.println("  FAIL flint.selfhost/main is unbound after the initialisers");
            System.exit(1);
        }
        // ONE argument: it dispatches on the first, and anything that is not
        // "wasm" or "project" is the SPEC form -- a map of already-resolved
        // `:sources`, which is what `--emit-spec` writes.
        System.out.println("  .. selfhost/main is " + compiler.getClass().getSimpleName());
        Object out;
        try {
            out = vm.call(compiler, new Object[]{ List.of(spec) });
        } catch (Vm.Thrown t) {
            // The compiler wraps an error with where it happened, so the useful
            // part is inside. Print it the way flint prints, not the way Java
            // does.
            System.out.println("  the compiler threw: " + Builtins.prStr(t.value));
            if (t.value instanceof Ex e) {
                System.out.println("  kind: " + e.kind);
                System.out.println("  data: " + Builtins.prStr(e.data));
            }
            // The last calls MADE, when recording is on. Not a stack -- calls
            // that already returned are in it -- but for an error that is not a
            // runaway recursion, what ran just before it is the useful part.
            String recent = vm.recentCalls();
            if (!recent.isEmpty()) System.out.println("  last calls made:" + recent);
            throw t;
        }
        System.out.println("  .. compiler returned"
            + (vm.aotEnabled ? " (" + vm.compiledCount + " arities compiled)" : ""));

        String s = Builtins.str(out);
        if (s.startsWith("!missing")) {
            System.out.println("  FAIL the compiler could not resolve: " + s.replace('\n', ' '));
            System.exit(1);
        }
        // The spec form answers base64 bytecode on the first line.
        String first = s.split("\n", 2)[0];
        boolean looksLikeAnImage = first.length() > 100 && first.matches("[A-Za-z0-9+/=]+");
        if (!looksLikeAnImage) {
            System.out.println("  FAIL the compiler did not emit an image");
            System.out.println("        got: " + s.substring(0, Math.min(300, s.length())));
            System.exit(1);
        }
        System.out.println("  ok   the flint compiler ran on the JVM and emitted "
                           + first.length() + " base64 chars");

        // "It emitted base64" is not the claim worth making. The claim is that
        // the SAME source through the SAME compiler produces the SAME image
        // whichever runtime the compiler itself ran on -- so the second
        // argument is a reference produced by the native path, and the two are
        // compared byte for byte.
        //
        // Byte for byte rather than "both run and agree", because an image that
        // differs is a compiler that differs, and the difference will surface
        // in a program neither of these tests happens to run.
        if (args.length > 1 && !args[1].equals("--aot")) {
            String want = Files.readString(Path.of(args[1])).split("\n", 2)[0].trim();
            byte[] got = java.util.Base64.getDecoder().decode(first);
            byte[] ref = java.util.Base64.getDecoder().decode(want);
            if (java.util.Arrays.equals(got, ref)) {
                System.out.println("  ok   byte for byte the image the native compiler emits ("
                                   + got.length + " bytes)");
            } else {
                int at = -1;
                for (int i = 0; i < Math.min(got.length, ref.length); i++) {
                    if (got[i] != ref[i]) { at = i; break; }
                }
                System.out.println("  FAIL the image differs from the native compiler's: "
                                   + got.length + " bytes against " + ref.length
                                   + (at < 0 ? ", one a prefix of the other"
                                             : ", first differing byte at " + at));
                System.exit(1);
            }
        }
    }
}
