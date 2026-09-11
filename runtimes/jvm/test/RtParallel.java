import com.flint.rt.*;

/// Two executors, one heap (`DECISIONS.md#drivers`). Ported from
/// `runtime/tests/parallel.rs`.
///
/// The claim under test is not "it does not crash". It is that a collection
/// staged by one thread walks the OTHER thread's roots, so objects that thread
/// is holding survive and the ones it moved are found where they moved to.
/// Allocation-heavy on purpose: the interesting window is the one where the
/// collector is copying while another executor has live values on its stack.
///
/// These are REAL HOST THREADS -- `java.lang.Thread` -- sharing one flint heap.
/// That is a different thing from the green threads in `Conc`, and both exist:
/// green threads are how one sandbox interleaves its own work deterministically,
/// and this is how a host drives one sandbox with a pool.
public class RtParallel {
  static int fails = 0;

  static void ok(String what, boolean cond) {
    System.out.println((cond ? "  ok   " : "  FAIL ") + what);
    if (!cond) fails++;
  }

  /// Build `n` cons cells and read every one back.
  ///
  /// Allocates constantly and holds a growing live set, so the nursery fills
  /// repeatedly and each collection has real work to move. Everything stays on
  /// the ROOT STACK across the allocations, which is the discipline that has to
  /// hold across ANOTHER thread's collection now too.
  ///
  /// Cons cells rather than strings on purpose: `Str.of` INTERNS, and the
  /// intern tables are shared state the allocation lock does not cover.
  static int churn(Rt rt, long tag, int n) {
    // From here this thread can be stopped for a collection; after `leaveGuest`
    // nothing waits for it. Getting this bracket wrong is not a subtle bug: the
    // Rust's first version without it HUNG, because the collector waited for an
    // executor that had already finished.
    rt.enterGuest();
    int base = rt.mark();
    for (int i = 0; i < n; i++) {
      rt.push(Seqs.cons(rt, Val.fixnum(tag * 1_000_000 + i), Val.NIL));
    }
    // A cell whose pointer was not fixed up after a move reads as the WRONG
    // NUMBER here, rather than crashing. That is the failure worth catching: a
    // crash would at least be obvious.
    for (int i = 0; i < n; i++) {
      long got = com._3sln.flint.kgen.rt.Seqwalk.first(rt, rt.r(base + i));
      if (!Val.isFixnum(got) || Val.asFixnum(got) != tag * 1_000_000 + i) {
        throw new IllegalStateException(
          "thread " + tag + ": slot " + i + " came back wrong: " + rt.describe(got));
      }
    }
    rt.popTo(base);
    rt.leaveGuest();
    return n;
  }

  public static void main(String[] a) throws Exception {
    // --- two executors, one heap, both allocating hard.
    Rt primary = new Rt(64 * 1024, 256L * 1024 * 1024);
    Rt secondary = primary.executor();
    ok("a second executor registers", primary.roots.shared.par.executors() == 2);

    final int n = 20_000;
    final Throwable[] err = new Throwable[1];
    Thread t = new Thread(() -> {
      try { churn(secondary, 2, n); } catch (Throwable e) { err[0] = e; }
    });
    t.start();
    churn(primary, 1, n);
    t.join();
    if (err[0] != null) {
      System.out.println("  FAIL the second executor: " + err[0]);
      err[0].printStackTrace();
      System.exit(1);
    }
    ok("two executors churned " + n + " cells each on one heap", true);

    // Both really did collect, or the test proved nothing about collection. A
    // run in which nothing was collected proves nothing. This says HOW MANY, so
    // a change that quietly stops the nursery filling shows up as a weaker test
    // rather than as a passing one.
    long minor = primary.gc.minors;
    ok("and the nursery was under real pressure: " + minor + " collections", minor >= 4);
    secondary.close();

    // --- a lone executor takes no lock and stages no safepoint.
    Rt lone = new Rt(64 * 1024, 64L * 1024 * 1024);
    ok("a lone sandbox has one executor and polls nothing",
       lone.roots.shared.par.executors() == 1 && !lone.safepoints);

    // --- interning agrees across executors.
    //
    // Two threads interning the same text must get ONE object, or `=` on two
    // interned strings -- a pointer compare -- answers false for equal values.
    Rt p2 = new Rt(1024 * 1024, 128L * 1024 * 1024);
    Rt s2 = p2.executor();
    final long[] got = new long[2];
    // Longer than INLINE_MAX (5) so it is on the heap, and no longer than
    // INTERN_MAX (32) so it is INTERNED. Outside that window the test would
    // pass or fail for the wrong reason: an inline string is equal by
    // construction, and one past the threshold is deliberately not interned at
    // all.
    final String text = "interned-across-executors";
    Thread t2 = new Thread(() -> {
      s2.enterGuest();
      int b = s2.mark();
      s2.push(Str.of(s2, text));
      got[1] = s2.r(b);
      s2.popTo(b);
      s2.leaveGuest();
    });
    t2.start();
    p2.enterGuest();
    int b2 = p2.mark();
    p2.push(Str.of(p2, text));
    got[0] = p2.r(b2);
    p2.popTo(b2);
    p2.leaveGuest();
    t2.join();
    ok("the text is in the interned window: " + text.length() + " bytes",
       text.length() > Val.INLINE_MAX && text.length() <= Interns.INTERN_MAX);
    ok("two executors interning one text agree on ONE object", got[0] == got[1]);
    s2.close();

    if (fails > 0) { System.out.println("  " + fails + " failed"); System.exit(1); }
  }
}
