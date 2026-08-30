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
    // `a[1]`, when given, is the answer the NATIVE runtime produced for this
    // same image. That is the whole point: not that the port runs, but that it
    // agrees. Without it this test can only report that nothing threw.
    String want = a.length > 1 ? a[1] : null;
    Rt rt = new Rt(1024 * 1024, 64L * 1024 * 1024);
    Img.Loaded img = Img.load(rt, Files.readAllBytes(Path.of(a[0])));
    if (img == null) { System.out.println("  FAIL not a flint image, or a version this runtime does not speak"); System.exit(1); }
    System.out.println("  ok   loads a real image: " + rt.fns.length + " fns, " + rt.consts.length
      + " consts, " + rt.code.length + " code bytes, entry=" + img.entry
      + ", " + img.nativeNames.length + " natives, " + img.init.length + " initialisers");
    // The initialisers first, in order: a program's top-level forms.
    //
    // AND CHECKED. A flint throw is not a host exception -- it sets `thrown`
    // and unwinds to the top, where `call` returns nil -- so ignoring it means
    // a program whose top-level `assert` FAILED runs on to `main` and reports
    // whatever `main` says. That is how this harness scored the jank suite
    // HIGHER on the ported runtime than on the native one: it was not noticing
    // the failed assertions, and a port that beats the thing it mirrors is
    // never good news.
    for (int fn : img.init) {
      rt.call(rt.makeClosure(fn, new long[0]), new long[0]);
      if (!Val.isNil(rt.thrown)) { System.out.println("  FAIL " + why(rt)); System.exit(1); }
    }
    long f = rt.makeClosure(img.entry, new long[0]);
    try {
      long v = rt.runProgram(f, new long[]{ Val.NIL });
      if (!Val.isNil(rt.thrown)) { System.out.println("  FAIL " + why(rt)); System.exit(1); }
      String shown = Val.isFixnum(v) ? String.valueOf(Val.asFixnum(v))
                   : Str.isString(rt, v) ? Str.text(rt, v)
                   : Val.isNil(v) ? "nil"
                   : "0x" + Long.toHexString(v);
      if (want != null && !want.equals(shown)) {
        System.out.println("  FAIL the ported runtime DISAGREES with the native one");
        System.out.println("        native " + want);
        System.out.println("        ported " + shown);
        System.exit(1);
      }
      System.out.println("  ok   main -> " + shown
                         + (want != null ? "  (the native runtime agrees)" : ""));
    } catch (UnsupportedOperationException e) {
      System.out.println("  .. as far as: " + e.getMessage());
    }
  }

  /// What was thrown, said in flint's terms rather than the host's.
  static String why(Rt rt) {
    long t = rt.thrown;
    if (rt.isException(t) && Str.isString(rt, rt.exMessage(t))) {
      return "the program threw " + Str.text(rt, rt.exKind(t))
             + ": " + Str.text(rt, rt.exMessage(t));
    }
    return "the program threw " + rt.describe(t);
  }
}
