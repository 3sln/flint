import com.flint.rt.*;

/// THE HOST-PORT INSTALL PATH, under a collection at every allocation.
///
/// This is the one way into the runtime that had no stress coverage at all.
/// `bin/conform-hosts` re-runs every conformance program under
/// `-Dflint.gcstress`, and not one of them installs a host port -- the
/// programs that do are driven from the host side, which the stress row did
/// not reach.
///
/// What lived there: `installBridgePort` built the scheduler BEFORE rooting
/// the label it had been handed, on all three runtimes. The label arrives as a
/// host local, the scheduler's construction allocates, and under stress it
/// always collects -- so the push rooted the address a moved string used to
/// have. The jvm died on it in one run, `to-space overflow` out of
/// `Gc.forward` reached through `Roots.forEach`. Fixed in
/// `kin/portinstall.kin`, where the order is now stated once.
///
/// DELIBERATELY SMALL. The obvious guard was to re-run `RtHostPorts` under the
/// flag, and that trips over a SECOND and older rooting bug on the settle path
/// (`doc/goals/kin-port.md`, "a bad value-stack root"). A guard that cannot go
/// green is a guard nobody keeps, so this covers the install and says so.
public class RtRooting {
  public static void main(String[] a) {
    Rt rt = new Rt(4L * 1024 * 1024, 512L * 1024 * 1024);
    // A FRESH heap string, handed straight in as a host local -- which is what
    // a host actually does, and what makes the label movable.
    long label = Str.of(rt, "system");
    String want = Str.text(rt, label);
    // No scheduler yet, so the install has to build one, which allocates.
    long p = Conc.installSystemPort(rt, 1, label);
    if (Val.isNil(p)) {
      System.out.println("  FAIL installing the system port answered nil");
      System.exit(1);
    }
    long got = rt.slot(p, Conc.PT_LABEL);
    if (!Str.isString(rt, got)) {
      System.out.println("  FAIL the port's label is not a string any more: " + rt.describe(got));
      System.exit(1);
    }
    String have = Str.text(rt, got);
    if (!want.equals(have)) {
      System.out.println("  FAIL the label moved: wanted " + want + ", the port has " + have);
      System.exit(1);
    }
    // AND A SECOND INSTALL OF THE SAME ID hands back the same object rather
    // than minting a second end for one host handle.
    long again = Conc.installSystemPort(rt, 1, Str.of(rt, "other"));
    if (again != p) {
      System.out.println("  FAIL installing the same id twice made a second port");
      System.exit(1);
    }
    System.out.println("  ok   a host port installed into a fresh sandbox keeps its label");
  }
}
