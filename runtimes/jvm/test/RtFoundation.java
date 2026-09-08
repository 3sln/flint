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
            gcStress();
            interpreter();
            System.out.printf("    3,000,000 iterations, best of 7: %.2f ns/iteration%n", ns);
            System.out.printf("    against 85 ns boxed on the current port -- %.0fx%n", 85.0 / ns);
        }
    }

    /// The interpreter, on hand-assembled bytecode.
    ///
    /// Hand-assembled rather than loaded from an image, so this tests the
    /// DISPATCH LOOP and nothing else -- the image loader and the builtins are
    /// not ported yet, and a failure here would otherwise have three possible
    /// causes instead of one.
    ///
    /// The answers are the ones the Rust gives for the same bytes, which is the
    /// point: `runtime/tests/vm.rs` assembles the same shapes.
    static void interpreter() {
        Rt rt = new Rt(256 * 1024, 16L * 1024 * 1024);

        // fn 0: (fn [n] (loop [i 0 acc 0] (if (< i n) (recur (inc i) (+ acc i)) acc)))
        // as a self tail call, which is what `recur` compiles to.
        //
        //   locals: 0 = n, 1 = i, 2 = acc
        Asm a = new Asm();
        a.op(Op.INT).i16(0).op(Op.SET_LOCAL).u8(1);      // i = 0
        a.op(Op.INT).i16(0).op(Op.SET_LOCAL).u8(2);      // acc = 0
        int top = a.at();
        a.op(Op.LOCAL).u8(1).op(Op.LOCAL).u8(0).op(Op.LT_INT);
        int exit = a.op(Op.JUMP_IF_FALSE).hole();
        a.op(Op.LOCAL).u8(2).op(Op.LOCAL).u8(1).op(Op.ADD_INT).op(Op.SET_LOCAL).u8(2);
        a.op(Op.LOCAL).u8(1).op(Op.INT).i16(1).op(Op.ADD_INT).op(Op.SET_LOCAL).u8(1);
        a.jumpTo(top);
        a.patch(exit);
        a.op(Op.LOCAL).u8(2).op(Op.RETURN);

        rt.code = a.done();
        rt.fns = new Rt.FnDef[]{
            new Rt.FnDef(new Rt.Arity[]{ new Rt.Arity(1, false, 3, 0, rt.code.length) }, 0)
        };
        long f = rt.makeClosure(0, new long[0]);
        long got = rt.call(f, new long[]{ Val.fixnum(1000) });
        long want = 1000L * 999 / 2;
        if (Val.asFixnum(got) != want)
            throw new AssertionError("loop gave " + Val.asFixnum(got) + " want " + want);
        System.out.printf("  ok   the interpreter runs a counting loop (%,d in %,d steps)%n",
                          Val.asFixnum(got), rt.steps);

        // A CALL and a RETURN across frames, so `fp`/`retTo` are exercised
        // rather than assumed: fn 1 calls fn 0 and adds one.
        Asm b = new Asm();
        b.op(Op.CLOSURE).u16(0).u8(0);
        b.op(Op.LOCAL).u8(0);
        b.op(Op.CALL).u8(1);
        b.op(Op.INT).i16(1).op(Op.ADD_INT).op(Op.RETURN);
        byte[] second = b.done();
        byte[] both = new byte[rt.code.length + second.length];
        System.arraycopy(rt.code, 0, both, 0, rt.code.length);
        System.arraycopy(second, 0, both, rt.code.length, second.length);
        int off = rt.code.length;
        rt.code = both;
        rt.fns = new Rt.FnDef[]{
            new Rt.FnDef(new Rt.Arity[]{ new Rt.Arity(1, false, 3, 0, off) }, 0),
            new Rt.FnDef(new Rt.Arity[]{ new Rt.Arity(1, false, 2, off, second.length) }, 0),
        };
        long g = rt.makeClosure(1, new long[0]);
        long got2 = rt.call(g, new long[]{ Val.fixnum(100) });
        if (Val.asFixnum(got2) != 100L * 99 / 2 + 1)
            throw new AssertionError("nested call gave " + Val.asFixnum(got2));
        System.out.println("  ok     ... and a call and return across frames");

        // A TAIL CALL runs in constant space. Without dropping the frame first
        // this overflows; with it, the frame stack never exceeds two.
        // `(fn rec [n] (if (> n 0) (rec (dec n)) n))`.
        //
        // The comparison is EXPLICIT: 0 is truthy in Clojure, so branching on
        // `n` itself would never terminate. That is not a detail of this test --
        // it is the rule the whole language rests on, and the first version of
        // this bytecode got it wrong and span.
        Asm c = new Asm();
        c.op(Op.LOCAL).u8(0).op(Op.INT).i16(0).op(Op.GT_INT);
        int done = c.op(Op.JUMP_IF_FALSE).hole();
        c.op(Op.SELF);
        c.op(Op.LOCAL).u8(0).op(Op.INT).i16(1).op(Op.SUB_INT);
        c.op(Op.TAIL_CALL).u8(1);
        c.patch(done);
        c.op(Op.LOCAL).u8(0).op(Op.RETURN);
        rt.code = c.done();
        rt.fns = new Rt.FnDef[]{
            new Rt.FnDef(new Rt.Arity[]{ new Rt.Arity(1, false, 2, 0, rt.code.length) }, 0)
        };
        long h = rt.makeClosure(0, new long[0]);
        int before = rt.frames.size();
        long got3 = rt.call(h, new long[]{ Val.fixnum(200_000) });
        if (Val.asFixnum(got3) != 0)
            throw new AssertionError("tail recursion gave " + Val.asFixnum(got3));
        if (rt.frames.size() != before)
            throw new AssertionError("frames leaked: " + before + " -> " + rt.frames.size());
        // THE EMPTY VECTOR AND MAP ARE SHARED SINGLETONS, and eleven lines in
    // `Conc` depend on it without saying so:
    //
    //     rt.setSlot(Val.asHeap(rt.r(si)), SC_EVENTS, Vec.empty(rt));
    //
    // That reads a root into a raw ADDRESS and then calls `Vec.empty`. If
    // `Vec.empty` ever allocated, the address would be stale before it was
    // used -- eleven `0031` violations appearing at once, in the runtime
    // rather than in a test.
    //
    // Native asserts this (`empty_vector_is_a_shared_singleton`) and RUST
    // WOULD NOT COMPILE the shape anyway: two `&mut self` calls in one
    // expression. Java and C# accept it, so the property they lean on is
    // asserted here instead.
    {
        Rt sg = new Rt(64 * 1024, 1024L * 1024);
        if (Vec.empty(sg) != Vec.empty(sg))
            throw new AssertionError("the empty vector is not a shared object");
        if (Maps.empty(sg) != Maps.empty(sg))
            throw new AssertionError("the empty map is not a shared object");
        // NOT "the address is stable": a moving collector relocates the
        // singleton and updates the root that holds it, so the address SHOULD
        // change. The property is that it stays ONE object and a valid one.
        // Asserting the address first is how this test failed against a
        // correct runtime.
        int base = sg.mark();
        for (int i = 0; i < 20000; i++) sg.push(Val.heap(sg.alloc(Obj.TY_VEC, 4)));
        sg.gc.minor(sg.roots);
        sg.popTo(base);
        if (Vec.empty(sg) != Vec.empty(sg))
            throw new AssertionError("the empty vector stopped being shared after a collection");
        if (Vec.count(sg, Vec.empty(sg)) != 0)
            throw new AssertionError("the empty vector is not empty after a collection");
        System.out.println("  ok   the empty vector and map are shared, and survive a collection");
    }

    System.out.println("  ok     ... and 200,000 tail calls in constant frame space");
    }

    /// A tiny assembler, the same shape as the Rust tests'.
    static final class Asm {
        private byte[] b = new byte[64];
        private int n = 0;
        int at() { return n; }
        private void put(int x) {
            if (n == b.length) { byte[] g = new byte[n * 2]; System.arraycopy(b, 0, g, 0, n); b = g; }
            b[n++] = (byte) x;
        }
        Asm op(int o) { put(o); return this; }
        Asm u8(int v) { put(v); return this; }
        Asm u16(int v) { put(v & 0xFF); put((v >> 8) & 0xFF); return this; }
        Asm i16(int v) { return u16(v & 0xFFFF); }
        int hole() { int h = n; put(0); put(0); return h; }
        void patch(int h) { int off = n - (h + 2); b[h] = (byte) (off & 0xFF); b[h + 1] = (byte) ((off >> 8) & 0xFF); }
        void jumpTo(int target) { put(Op.JUMP); int off = target - (n + 2); put(off & 0xFF); put((off >> 8) & 0xFF); }
        byte[] done() { byte[] out = new byte[n]; System.arraycopy(b, 0, out, 0, n); return out; }
    }

    /// The collector, under pressure, with the invariant asserted rather than
    /// hoped for.
    ///
    /// Builds a linked list far larger than the nursery, so it is collected
    /// many times over and every survivor is copied, promoted, and pointed at
    /// from the old generation. Then walks it. A collector that loses ONE
    /// object, or forwards one pointer wrongly, produces a wrong sum -- and the
    /// walk is what turns "it did not crash" into a result.
    static void gcStress() {
        Gc gc = new Gc(256 * 1024, 64L * 1024 * 1024);
        Roots roots = new Roots();
        final int N = 200_000;

        // A cons list, held only through the shadow stack: the Java local goes
        // stale at the first collection, which is the whole point of `push`/`r`.
        int head = roots.push(Val.NIL);
        for (int i = 0; i < N; i++) {
            long cell = gc.alloc(roots, Obj.TY_CONS, 4);
            if (cell == 0) throw new AssertionError("out of heap at " + i);
            // The rest pointer is read back from the shadow stack AFTER the
            // allocation that could have moved it.
            gc.setSlot(cell, 0, Val.fixnum(i), roots);
            gc.setSlot(cell, 1, roots.r(head), roots);
            roots.setR(head, Val.heap(cell));
        }

        long want = (long) N * (N - 1) / 2;
        long sum = 0;
        int seen = 0;
        long cur = roots.r(head);
        while (!Val.isNil(cur)) {
            long a = Val.asHeap(cur);
            sum += Val.asFixnum(Obj.slot(gc.sp, a, 0));
            seen++;
            cur = Obj.slot(gc.sp, a, 1);
        }
        if (seen != N) throw new AssertionError("walked " + seen + " of " + N);
        if (sum != want) throw new AssertionError("sum " + sum + " want " + want);
        System.out.printf("  ok   %,d objects survive %,d minor and %,d major collections intact%n",
                          N, gc.minors, gc.majors);

        // Garbage really is reclaimed: drop the list and collect. If nothing is
        // freed, the assertion above passed for the wrong reason -- a collector
        // that keeps everything loses nothing.
        long before = gc.heapUsed();
        roots.setR(head, Val.NIL);
        gc.major(roots);
        if (gc.heapUsed() >= before)
            throw new AssertionError("nothing was reclaimed: " + before + " -> " + gc.heapUsed());
        System.out.printf("  ok     ... and dropping them reclaims %,d bytes%n",
                          before - gc.heapUsed());
    }
}
