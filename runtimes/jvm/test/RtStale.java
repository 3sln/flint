import com.flint.rt.*;

/// THE STALE-PUSH CHECK, CHECKED.
///
/// `doc/HANDOFF.md`: a value read into a host local before an allocation comes
/// back holding the address the object had before the collection moved it. The
/// address still tests young, so the write barrier passes and the generational
/// invariant passes, and one document wave in sixty-four went missing while the
/// run reported success. Nothing downstream can see it.
///
/// Native has asserted this check since that hunt. The JVM port had it behind
/// `-Dflint.stale` and nothing ever set the property; the CLR port did not have
/// it at all. So this asks the two questions that make the check worth having:
/// does it fire on the bad thing, and does it stay quiet on the good one.
public class RtStale {
    public static void main(String[] args) {
        if (!Rt.STALE_CHECK) {
            System.out.println("  FAIL the stale check is not enabled (set FLINT_STALE)");
            System.exit(1);
        }
        // GOOD: the value is ROOTED before anything allocates, so it is moved
        // with the heap and the local is never read across a collection.
        Rt rt = new Rt(64 * 1024, 1024L * 1024);
        int base = rt.mark();
        int vi = rt.push(Str.of(rt, "a value that lives on the heap"));
        for (int i = 0; i < 20000; i++) Str.of(rt, "filler " + i);
        rt.push(rt.r(vi));
        rt.popTo(base);
        if (Rt.staleCount != 0) {
            System.out.println("  FAIL a correctly rooted value tripped the stale check");
            System.exit(1);
        }
        System.out.println("  ok   a rooted value survives 20000 allocations quietly");

        // BAD: the same value in a HOST LOCAL across the same allocations.
        Rt rt2 = new Rt(64 * 1024, 1024L * 1024);
        int b2 = rt2.mark();
        long v = Str.of(rt2, "a value that lives on the heap");
        for (int i = 0; i < 20000; i++) Str.of(rt2, "filler " + i);
        rt2.push(v);
        rt2.popTo(b2);
        if (Rt.staleCount == 0) {
            System.out.println("  FAIL the stale check did NOT fire on a stale push");
            System.exit(1);
        }
        System.out.println("  ok   a value carried across a collection is caught");
    }
}
