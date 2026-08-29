import com.flint.rt.*;
import java.util.Arrays;

/// Snapshots on the ported runtime: both formats, and the properties that
/// distinguish them.
///
/// The two are NOT redundant. The verbatim one is a memcpy that must come back
/// at identical addresses; the live one traverses and must come back CORRECT AT
/// DIFFERENT ONES. A test that only checked "the data is still there" would
/// pass for the live format even if it had quietly kept the old addresses --
/// so the live case deliberately imports into a heap with a different nursery
/// size, and then asserts the addresses MOVED as well as that the data is
/// intact. Without that second assertion the interesting half is untested.
public class RtSnapshot {
  static int fails = 0;

  static void ok(String what, boolean cond) {
    System.out.println((cond ? "  ok   " : "  FAIL ") + what);
    if (!cond) fails++;
  }

  /// A structure with enough shape to be wrong in a detectable way: a vector of
  /// vectors, so the export has to get INTERIOR references right, plus a seq so
  /// there is a cons chain, plus a string so a non-`Vals` layout is exercised.
  static long build(Rt rt, int n) {
    int base = rt.mark();
    int vec = rt.push(Vec.empty(rt));
    for (int i = 0; i < n; i++) {
      int inner = rt.push(Vec.empty(rt));
      rt.setR(inner, Vec.conj(rt, rt.r(inner), Val.fixnum(i)));
      rt.setR(inner, Vec.conj(rt, rt.r(inner), Str.of(rt, "item-" + i)));
      rt.setR(vec, Vec.conj(rt, rt.r(vec), rt.r(inner)));
      rt.popTo(inner);
    }
    long out = rt.r(vec);
    rt.popTo(base);
    return out;
  }

  /// Read the structure back as a string, so one comparison covers every
  /// element, its type, and its order.
  static String render(Rt rt, long v) {
    StringBuilder sb = new StringBuilder();
    int n = Vec.count(rt, v);
    for (int i = 0; i < n; i++) {
      long inner = Vec.nth(rt, v, i);
      sb.append(Val.asFixnum(Vec.nth(rt, inner, 0)));
      sb.append('=');
      sb.append(Str.text(rt, Vec.nth(rt, inner, 1)));
      sb.append(';');
    }
    return sb.toString();
  }

  public static void main(String[] args) {
    final int N = 300;

    // --- the verbatim format: a memcpy, restored at identical addresses.
    Rt a = new Rt(1024 * 1024, 64L * 1024 * 1024);
    a.fingerprint = 0xABCDEF12345L;
    long root = build(a, N);
    a.roots.globals = new long[]{root};
    String before = render(a, root);
    ok("built a structure to snapshot: " + N + " entries", before.startsWith("0=item-0;"));

    byte[] verbatim = Snap.capture(a);
    ok("capture produced bytes: " + verbatim.length, verbatim.length > 1024);

    Rt b = new Rt(1024 * 1024, 64L * 1024 * 1024);
    b.fingerprint = a.fingerprint;
    b.roots.globals = new long[1];
    ok("restore accepts it", Snap.restore(b, verbatim));
    ok("the restored heap reads back identically", render(b, b.roots.globals[0]).equals(before));
    ok("and at the SAME address, which is what a memcpy means",
       Val.asHeap(b.roots.globals[0]) == Val.asHeap(root));

    // A snapshot restored against a DIFFERENT program does not fail -- it
    // quietly means something else. The fingerprint is what makes that
    // refusable, so check that it actually refuses.
    Rt wrong = new Rt(1024 * 1024, 64L * 1024 * 1024);
    wrong.fingerprint = 0x999L;
    ok("refuses a snapshot from another image", !Snap.restore(wrong, verbatim));
    ok("and says WHICH check failed", Snap.refused == Snap.REFUSE_IMAGE);
    byte[] corrupt = Arrays.copyOf(verbatim, verbatim.length);
    corrupt[4] = (byte) 0xFF;                       // the version word
    ok("refuses a layout it does not speak", !Snap.restore(b, corrupt));
    ok("and distinguishes that from a wrong image", Snap.refused == Snap.REFUSE_LAYOUT);

    // --- the live format: a traversal, and it must RELOCATE.
    Rt c = new Rt(1024 * 1024, 64L * 1024 * 1024);
    c.fingerprint = 0xABCDEF12345L;
    long croot = build(c, N);
    c.roots.globals = new long[]{croot};
    byte[] live = Snap.exportLive(c);
    ok("exportLive agrees with the collector about what is live", live != null);
    ok("and is smaller than the memcpy, being the data rather than the heap: "
       + live.length + " < " + verbatim.length, live.length < verbatim.length);

    // A DIFFERENT nursery, so nothing can come back where it started by luck.
    Rt d = new Rt(3 * 1024 * 1024, 64L * 1024 * 1024);
    d.fingerprint = c.fingerprint;
    // Sized as an image load would size it. The import fills var slots, it
    // does not create them: the slots belong to the program, and a snapshot
    // that could add them would be carrying code after all.
    d.roots.globals = new long[1];
    ok("importLive accepts it", Snap.importLive(d, live));
    ok("the rehydrated heap reads back identically", render(d, d.roots.globals[0]).equals(before));
    ok("at a DIFFERENT address, which is what relocating means",
       Val.asHeap(d.roots.globals[0]) != Val.asHeap(croot));

    // The rehydrated heap has to be a working heap, not just a readable one:
    // keep allocating on it and collect, which is what would trip a bad
    // remembered set or a missed write barrier from pass two.
    long more = build(d, 200);
    d.roots.globals = new long[]{d.roots.globals[0], more};
    d.gc.major(d.roots);
    ok("survives a major collection after import",
       render(d, d.roots.globals[0]).equals(before));
    ok("and the objects allocated after it are intact too",
       render(d, d.roots.globals[1]).startsWith("0=item-0;"));

    // --- shelving: the halt that leaves nothing runnable.
    Snap.halt(d);
    ok("halt leaves nothing to run", d.frames.isEmpty() && d.roots.stackTop == 0);
    ok("and says the sandbox was shelved rather than answered",
       d.status == Snap.STATUS_SHELVED);

    if (fails > 0) { System.out.println("  " + fails + " failed"); System.exit(1); }
  }
}
