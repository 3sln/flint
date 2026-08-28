import com.flint.Img;

/// The loader, against a REAL image the flint compiler produced.
///
/// Not a synthetic fixture: the point of the bytecode being the portable
/// artifact (`doc/decisions/0010`) is that a host reads what the compiler
/// actually emits, and a hand-written fixture would agree with my reading of
/// the format rather than with the format.
public class LoadTest {
    static int failures = 0;

    static void ok(boolean cond, String what) {
        System.out.println("  " + (cond ? "ok  " : "FAIL") + " " + what);
        if (!cond) failures++;
    }

    public static void main(String[] args) throws Exception {
        byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(args[0]));
        Img img = Img.read(bytes);

        ok(img.fns.length > 0, "it has a function table (" + img.fns.length + " functions)");
        ok(img.consts.length > 0, "and constants (" + img.consts.length + ")");
        ok(img.code.length > 0, "and bytecode (" + img.code.length + " bytes)");
        ok(img.entry >= 0 && img.entry < img.fns.length, "and an entry that is a real function");
        ok(img.nativeNames.length > 0,
           "and its builtins BY NAME (" + img.nativeNames.length + "), not by slot");

        // The names the program defined have to be findable, or nothing else
        // in a port can be checked against anything.
        boolean sawMain = false, sawAdd = false;
        for (Img.FnDef f : img.fns) {
            if (f.name == null) continue;
            if (f.name.contains("main")) sawMain = true;
            if (f.name.contains("add")) sawAdd = true;
        }
        ok(sawMain, "the entry function is named in the image");
        ok(sawAdd, "and so is a function it calls");

        // Arity selection is a semantic rule, not a detail: a program that
        // dispatches differently per host is not one program.
        Img.FnDef f = img.fns[img.entry];
        ok(f.select(1) != null, "the entry selects an arity for one argument");
        ok(f.arities.length > 0 && f.arities[0].len > 0, "and its code has a length");

        // Every native the image imports must be a name this host can resolve.
        for (String n : img.nativeNames) {
            if (n == null || n.isEmpty()) { ok(false, "a native import has no name"); break; }
        }
        System.out.println("  natives: " + String.join(", ",
            java.util.Arrays.copyOfRange(img.nativeNames, 0, Math.min(6, img.nativeNames.length)))
            + (img.nativeNames.length > 6 ? ", ..." : ""));

        System.out.println(failures == 0 ? "\nall checks passed" : "\nFAILED");
        System.exit(failures == 0 ? 1 - 1 : 1);
    }
}
