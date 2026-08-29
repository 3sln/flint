import com.flint.rt.*;
import java.nio.file.*;

/// The ported runtime, on a REAL compiled image.
///
/// Everything before this ran on hand-assembled bytecode, which tests the
/// dispatch loop and nothing else. This loads what the flint compiler actually
/// emits -- constants, a function table, natives resolved by name, top-level
/// initialisers -- so it is the first check that the port can meet the
/// compiler rather than a test harness.
///
/// It reports HOW FAR it gets rather than passing or failing outright, because
/// the standard library is not ported yet and "loaded 85 functions and then
/// wanted `flint/num->str`" is the useful answer while that is true. A silent
/// pass here would be the misleading one.
public class RtImage {
  public static void main(String[] a) throws Exception {
    Rt rt = new Rt(1024 * 1024, 64L * 1024 * 1024);
    Img.Loaded img = Img.load(rt, Files.readAllBytes(Path.of(a[0])));
    if (img == null) { System.out.println("  FAIL not a flint image, or a version this runtime does not speak"); System.exit(1); }
    System.out.println("  ok   loads a real image: " + rt.fns.length + " fns, " + rt.consts.length
      + " consts, " + rt.code.length + " code bytes, entry=" + img.entry
      + ", " + img.nativeNames.length + " natives, " + img.init.length + " initialisers");
    // The initialisers first, in order: a program's top-level forms.
    for (int fn : img.init) rt.call(rt.makeClosure(fn, new long[0]), new long[0]);
    long f = rt.makeClosure(img.entry, new long[0]);
    try {
      long v = rt.call(f, new long[]{ Val.NIL });
      System.out.println("  ok   main -> " + (Val.isFixnum(v) ? String.valueOf(Val.asFixnum(v))
                                                              : "0x" + Long.toHexString(v)));
    } catch (UnsupportedOperationException e) {
      System.out.println("  .. as far as: " + e.getMessage());
    }
  }
}
