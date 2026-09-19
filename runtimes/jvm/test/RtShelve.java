import com.flint.rt.*;
import java.nio.file.*;

/// SHELVING A REAL PROGRAM, mid-run.
///
/// `RtSnapshot` builds a heap by hand and round-trips it. That checks the
/// format. It does NOT check the thing shelving is for: stopping a program that
/// is genuinely half-finished -- with frames on the stack, locals live, a loop
/// counter partway -- moving it, and having it carry on to the right answer.
///
/// The difference matters because a hand-built heap has no INTERPRETER STATE in
/// it. Frames, handlers, the value stack and the instruction pointer are all
/// captured too, and none of them were exercised by a heap of vectors.
///
/// So: load a real image, run it until the slice runs out, snapshot THERE,
/// rehydrate into a fresh runtime with a different nursery, and let it finish.
/// The answer must be the one the program produces when nothing interrupts it.
public class RtShelve {
  static int fails = 0;

  static void ok(String what, boolean cond) {
    System.out.println((cond ? "  ok   " : "  FAIL ") + what);
    if (!cond) fails++;
  }

  /// Run until the step budget trips, leaving the runtime mid-program.
  static boolean runUntilPaused(Rt rt, Img.Loaded img, long budget) {
    // THESE ARE THE INITIALISERS, so say so: `ensureStarted` is the runtime's
    // own one-shot runner, and a control plane spawned later would otherwise
    // run them a second time.
    rt.started = true;
    for (int fn : img.init) rt.call(rt.makeClosure(fn, new long[0]), new long[0]);
    long f = rt.makeClosure(img.entry, new long[0]);
    rt.setSliceEnd(budget);
    long v = rt.call(f, new long[]{ Val.NIL });
    return rt.parked();
  }

  public static void main(String[] a) throws Exception {
    byte[] image = Files.readAllBytes(Path.of(a[0]));
    String want = a[1];

    // What the program says when nothing interrupts it.
    Rt plain = new Rt(4 * 1024 * 1024, 128L * 1024 * 1024);
    Img.Loaded pimg = Img.load(plain, image);
    plain.started = true;
    for (int fn : pimg.init) plain.call(plain.makeClosure(fn, new long[0]), new long[0]);
    String straight = show(plain, plain.call(plain.makeClosure(pimg.entry, new long[0]),
                                             new long[]{ Val.NIL }));
    ok("the program runs straight through to " + trim(straight), straight.equals(want));

    for (String kind : new String[]{ "verbatim", "live" }) {
      Rt rt = new Rt(4 * 1024 * 1024, 128L * 1024 * 1024);
      Img.Loaded img = Img.load(rt, image);
      // Partway: enough to be deep in the program, not enough to finish. The
      // budget is a fraction of what the program takes, so the pause lands
      // inside a loop with frames live rather than at a tidy boundary.
      boolean paused = runUntilPaused(rt, img, 5000);
      ok(kind + ": stopped mid-program with " + rt.frames.size() + " frames live", paused);

      byte[] snap = kind.equals("verbatim") ? Snap.capture(rt) : Snap.exportLive(rt);
      ok(kind + ": snapshot taken of a half-finished program (" + snap.length + " bytes)",
         snap != null && snap.length > 0);

      // A FRESH runtime. The image is loaded first, because a snapshot carries
      // no code -- that is the whole point of the fingerprint, and it is what
      // makes the snapshot small enough to move.
      Rt back = new Rt(kind.equals("live") ? 7 * 1024 * 1024 : 4 * 1024 * 1024,
                       128L * 1024 * 1024);
      Img.load(back, image);
      boolean took = kind.equals("verbatim") ? Snap.restore(back, snap)
                                             : Snap.importLive(back, snap);
      ok(kind + ": the fresh runtime accepts it", took);

      // And carries on. `run(0)` resumes the frames the snapshot restored --
      // there is no entry point to call, because the program is already inside
      // itself.
      // `resume`, not `run`: a snapshot is FAITHFUL, so `thrown` comes back
      // holding the park that was in flight when it was taken. Ending a
      // slice pause is what resuming means, and it is what the scheduler's
      // `settle` does for a program that never stopped.
      back.setSliceEnd(0);
      String finished = show(back, back.resume());
      ok(kind + ": it carries on to the SAME answer", finished.equals(want));
      if (!finished.equals(want)) {
        System.out.println("        wanted " + trim(want));
        System.out.println("        got    " + trim(finished));
      }
    }

    // A snapshot of one program must not load into another. Refused by
    // FINGERPRINT, because every `ip` and constant index in it is an index into
    // the image it came from -- so the wrong image does not fail, it quietly
    // means something else.
    Rt other = new Rt(4 * 1024 * 1024, 128L * 1024 * 1024);
    Img.load(other, image);
    other.fingerprint ^= 1;
    ok("a snapshot is refused by a runtime holding a different program",
       !Snap.restore(other, Snap.capture(plain)));

    if (fails > 0) { System.out.println("  " + fails + " failed"); System.exit(1); }
  }

  static String show(Rt rt, long v) {
    return Val.isFixnum(v) ? String.valueOf(Val.asFixnum(v))
         : Str.isString(rt, v) ? Str.text(rt, v)
         : Val.isNil(v) ? "nil" : "0x" + Long.toHexString(v);
  }

  static String trim(String s) { return s.length() > 60 ? s.substring(0, 60) + "..." : s; }
}
