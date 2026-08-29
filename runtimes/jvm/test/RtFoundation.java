import com.flint.rt.*;

/// The ported runtime's foundation: values, memory and object layout.
///
/// ## What the number at the end means, and does not
///
/// The current JVM port runs a counting loop at 85 ns/iteration, and its AOT
/// emitter buys 1% -- because the interpreter and the compiled code box every
/// intermediate in exactly the same places. Boxing was the ceiling, not a
/// detail.
///
/// The loop below is the read-modify-write shape an interpreter's value stack
/// has, over this representation, with NO dispatch. So it is not an end-to-end
/// speedup and must not be quoted as one: it is the cost of the REPRESENTATION,
/// which was most of that 85 ns and is what a dispatch loop then sits on top
/// of. What it settles is that the ceiling moved.
public class RtFoundation {
    public static void main(String[] a) {
        // 1. Values round-trip across the whole 48-bit payload.
        for (long off : new long[]{8, 16, 0x1000, 0xFFFF_FFFFL, 0x1_0000_0000L,
                                   0x0000_FFFF_FFFF_FFF8L}) {
            long v = Val.heap(off);
            if (!Val.isHeap(v) || Val.asHeap(v) != off)
                throw new AssertionError("heap round trip failed at " + Long.toHexString(off));
        }
        for (long n : new long[]{0, 1, -1, 1L << 40, -(1L << 40)}) {
            if (Val.asFixnum(Val.fixnum(n)) != n)
                throw new AssertionError("fixnum round trip failed at " + n);
        }
        System.out.println("  ok   values round-trip across 48 bits");

        // 2. Objects: header, slots, forwarding.
        try (Space sp = new Space(64L * 1024 * 1024)) {
            long addr = sp.take(1024);
            Obj.writeHeader(sp, addr, Obj.TY_CONS, 4);
            if (Obj.ty(sp, addr) != Obj.TY_CONS || Obj.len(sp, addr) != 4)
                throw new AssertionError("header round trip failed");
            Obj.setSlotRaw(sp, addr, 0, Val.fixnum(42));
            if (Val.asFixnum(Obj.slot(sp, addr, 0)) != 42)
                throw new AssertionError("slot round trip failed");
            Obj.setMarked(sp, addr, true);
            if (!Obj.marked(sp, addr) || Obj.ty(sp, addr) != Obj.TY_CONS)
                throw new AssertionError("the mark bit disturbed the type");
            long far = 0x0000_FF00_1234_5678L;
            Obj.setForward(sp, addr, far);
            if (Obj.forwardTarget(sp, addr) != far)
                throw new AssertionError("a 48-bit forward did not survive the header");
            System.out.println("  ok   objects, mark bits and 48-bit forwarding");

            // 3. THE MEASUREMENT: a counting loop whose accumulator lives in
            // the space, so every iteration is a real read-modify-write of a
            // NaN-boxed value -- the shape the interpreter's value stack has.
            long stack = sp.take(1024);
            long best = Long.MAX_VALUE;
            for (int rep = 0; rep < 7; rep++) {
                sp.writeU64(stack, Val.fixnum(0));
                sp.writeU64(stack + 8, Val.fixnum(0));
                long t0 = System.nanoTime();
                for (int k = 0; k < 3_000_000; k++) {
                    long i = Val.asFixnum(sp.readU64(stack));
                    long acc = Val.asFixnum(sp.readU64(stack + 8));
                    sp.writeU64(stack, Val.fixnum(i + 1));
                    sp.writeU64(stack + 8, Val.fixnum(acc + i));
                }
                best = Math.min(best, System.nanoTime() - t0);
            }
            double ns = best / 3_000_000.0;
            System.out.printf("    3,000,000 iterations, best of 7: %.2f ns/iteration%n", ns);
            System.out.printf("    against 85 ns boxed on the current port -- %.0fx%n", 85.0 / ns);
        }
    }
}
