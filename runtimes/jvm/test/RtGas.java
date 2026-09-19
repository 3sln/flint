import com.flint.rt.*;
import java.nio.file.*;

/// GAS IS A BOUND, on the ports too (`DECISIONS.md#resource-limits`).
///
/// This test exists because it did not, and the hole it leaves is invisible to
/// everything else: `gasLimit` was a field these runtimes wrote into snapshots
/// and never read. The native runtime stopped a runaway program with a
/// catchable error; the JVM and CLR ports ran it to completion.
///
/// Conformance cannot catch that. It diffs ANSWERS between the runtimes, and a
/// program allowed to run forever eventually produces the right one -- so every
/// row stayed green while one of the three had no budget at all. A property
/// that is about REFUSING has to be tested by asking for the refusal.
public class RtGas {
  static int fails = 0;
  static void ok(String label, boolean cond, String extra) {
    if (cond) System.out.println("  ok   " + label);
    else { fails++; System.out.println("  FAIL " + label + "\n        " + extra); }
  }

  public static void main(String[] a) throws Exception {
    Rt rt = new Rt(1024 * 1024, 64L * 1024 * 1024);
    Img.Loaded img = Img.load(rt, Files.readAllBytes(Path.of(a[0])));
    if (img == null) { System.out.println("  FAIL not a flint image"); System.exit(1); }

    // GENEROUS, not absent. A limit of 0 means "not counting", and an
    // unbudgeted sandbox deliberately maintains no counter -- so measuring with
    // one would compare a counted run against an uncounted one and call the
    // difference a parity gap.
    rt.setGasLimit(0x7ffffff0L);
    // THESE ARE THE INITIALISERS, so say so: `ensureStarted` is the runtime's
    // own one-shot runner, and a control plane spawned later would otherwise
    // run them a second time.
    rt.started = true;
    for (int fn : img.init) rt.call(rt.makeClosure(fn, new long[0]), new long[0]);
    long f = rt.makeClosure(img.entry, new long[0]);
    rt.runProgram(f, new long[]{ Val.NIL });
    boolean threw = !Val.isNil(rt.thrown);
    ok("with no budget the program runs to its answer", !threw,
       threw ? Str.text(rt, rt.exMessage(rt.thrown)) : "");
    long spent = rt.steps;
    ok("  ... and the instructions were counted: " + spent, spent > 1000,
       "steps " + spent + " -- nothing counted, so the bound below proves nothing");

    // TIGHT: a fraction of what the program needs. It must stop, and it must
    // stop with an error that says what was spent against what was allowed --
    // a host has to be able to tell "the program is wrong" from "the budget was
    // too small".
    Rt rt2 = new Rt(1024 * 1024, 64L * 1024 * 1024);
    Img.Loaded img2 = Img.load(rt2, Files.readAllBytes(Path.of(a[0])));
    rt2.started = true;
    for (int fn : img2.init) rt2.call(rt2.makeClosure(fn, new long[0]), new long[0]);
    long limit = rt2.steps + spent / 4;
    rt2.setGasLimit(limit);
    rt2.runProgram(rt2.makeClosure(img2.entry, new long[0]), new long[]{ Val.NIL });
    boolean stopped = !Val.isNil(rt2.thrown);
    ok("a tight budget stops the program", stopped,
       "it ran to completion on " + limit + " gas when it needs " + spent);
    String msg = stopped ? Str.text(rt2, rt2.exMessage(rt2.thrown)) : "";
    ok("  ... with a catchable ResourceExhausted", msg.contains("gas limit exceeded"), msg);
    ok("  ... saying what was spent against what was allowed",
       msg.contains("of " + limit) || msg.contains("spent"), msg);
    // The OVERSHOOT is the property that matters: a budget a single call can
    // outrun is worse than no budget, because somebody will trust it.
    long over = rt2.steps - limit;
    ok("  ... near the limit rather than far past it (over by " + over + ")",
       over < 200000, "ran " + over + " steps past an exhausted budget");

    if (fails > 0) { System.out.println("gas: " + fails + " FAILURES"); System.exit(1); }
  }
}
