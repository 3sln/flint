import com.flint.rt.*;
import java.nio.file.*;

/// THE GENERATIONAL INVARIANT ON THE PORT, over a real program.
///
/// Every old object holding a young pointer must be in the remembered set. A
/// missed edge is a young object collected while an old one still points at
/// it: silent, and it surfaces somewhere else entirely. `doc/HANDOFF.md` is
/// what one of those cost, and until now only the native runtime asked.
///
/// The audit runs immediately after the old-space sweep, where every object
/// walked has just come through a mark. Two other placements were tried and
/// measured first -- see `Gc.auditLive` for why neither works.
///
/// THE COVERAGE IS ASSERTED, not just the verdict. Zero violations over zero
/// edges is what a broken check reports, and this program exists to produce
/// edges: compound keys force the map, set and transient paths that promote
/// containers into old space while their contents are still young.
public class RtGcAudit {
  public static void main(String[] a) throws Exception {
    if (!Gc.CHECKS) {
      System.out.println("  FAIL the gc audit is not enabled (set FLINT_STALE)");
      System.exit(1);
    }
    Rt rt = new Rt(1024 * 1024, 64L * 1024 * 1024);
    Img.Loaded img = Img.load(rt, Files.readAllBytes(Path.of(a[0])));
    if (img == null) { System.out.println("  FAIL not a flint image"); System.exit(1); }
    for (int fn : img.init) {
      rt.call(rt.makeClosure(fn, new long[0]), new long[0]);
      if (!Val.isNil(rt.thrown)) { System.out.println("  FAIL an initialiser threw"); System.exit(1); }
    }
    long v = rt.runProgram(rt.makeClosure(img.entry, new long[0]), new long[]{ Val.NIL });
    if (!Val.isNil(rt.thrown)) { System.out.println("  FAIL main threw"); System.exit(1); }
    // The answer is compared against the NATIVE runtime's, passed in, for the
    // same reason `RtImage` does it: that the port ran is not the claim.
    String shown = Val.isFixnum(v) ? String.valueOf(Val.asFixnum(v))
                 : Str.isString(rt, v) ? Str.text(rt, v)
                 : Val.isNil(v) ? "nil" : ("#" + Long.toHexString(v));
    if (a.length > 1 && !shown.equals(a[1])) {
      System.out.println("  FAIL the port answered " + shown + ", native said " + a[1]);
      System.exit(1);
    }
    if (Gc.liveWalkErrors > 0) {
      System.out.println("  FAIL the audit could not parse a span, so its zero means nothing");
      System.exit(1);
    }
    if (Gc.liveViolations > 0) {
      System.out.println("  FAIL " + Gc.liveViolations
                         + " live old objects point at young ones without being remembered");
      System.exit(1);
    }
    if (Gc.liveEdges < 100) {
      System.out.println("  FAIL the audit saw only " + Gc.liveEdges
                         + " old-to-young edges, so it checked almost nothing");
      System.exit(1);
    }
    System.out.println("  ok   the generational invariant holds on every live old object");
    System.out.println("  ok     " + Gc.liveEdges + " old-to-young edges over "
                       + Gc.liveSweeps + " sweeps, none unremembered");
  }
}
