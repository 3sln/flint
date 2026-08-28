import com.flint.*;

/// A real flint program, compiled by flint, running on the JVM.
public class RunTest {
    static int failures = 0;
    static void ok(boolean c, String what) {
        System.out.println("  " + (c ? "ok  " : "FAIL") + " " + what);
        if (!c) failures++;
    }
    static void eq(Object got, Object want, String what) {
        boolean c = Builtins.eq(got, want);
        System.out.println("  " + (c ? "ok  " : "FAIL") + " " + what
            + (c ? "" : "\n        want " + Builtins.prStr(want)
                      + "\n        got  " + Builtins.prStr(got)));
        if (!c) failures++;
    }

    public static void main(String[] args) throws Exception {
        byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(args[0]));
        Vm vm = new Vm(Img.read(bytes));
        vm.ensureStarted();

        // Call the entry the way a host does: by index, with the argument
        // vector the wasm ABI passes.
        Object out = vm.call(new Vm.Closure(vm.img.entry, new Object[0]),
                             new Object[]{ java.util.List.of() });
        eq(out, "5 hi flint", "the entry function runs and returns");

        System.out.println(failures == 0 ? "\nall checks passed" : "\nFAILED");
        if (failures != 0) System.exit(1);
    }
}
