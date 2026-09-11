package com.flint.rt;

import java.util.ArrayList;

import static com.flint.rt.Obj.*;

/// The collector, ported verbatim from `runtime/src/gc.rs`.
///
/// Generational and copying in the nursery, mark-and-sweep in the old space,
/// with a write barrier and a remembered set. Identical to the Rust because it
/// is the same design over the same flat memory -- which is the point of the
/// port: `DECISIONS.md#other-hosts` says the bytecode makes a port cheap and does
/// nothing to make two ports AGREE, and two collectors written two ways would
/// have to be argued into agreement rather than being the same thing.
public final class Gc {
    /// Objects surviving this many minors are promoted.
    ///
    /// TWO, NOT THREE, AND IT IS LOAD-BEARING. `DECISIONS.md#cross-runtime-benchmarks` bounds the
    /// largest single copy a collection may make at 512 KB, and
    /// `test/pause.clj` asserts it. At three, survivors sit in the nursery for
    /// one more collection and the largest copy measures 518.1 KB -- over the
    /// bound. This port carried 3 for as long as it existed and never ran that
    /// test, so it had quietly opted out of a decision the native runtime
    /// holds itself to.
    static final int PROMOTE_AGE = 2;
    /// Bigger than this goes straight to the old space: copying it twice costs
    /// more than the generational hypothesis is worth on one object.
    ///
    /// 16 KB, matching the Rust. At 8192 more objects skip the nursery, which
    /// is a different heap shape for no stated reason.
    static final long LARGE_OBJECT = 16384;
    /// 0..63 exact (size = i*8), 64 = "big".
    static final int NCLASS = 65;
    static final long MIN_CHUNK = 1024 * 1024;

    public final Space sp;

    long youngBase, half, from, to, toBump, bump, fromEnd;

    /// Old-space chunks, and the free lists that carve them up.
    final ArrayList<long[]> oldChunks = new ArrayList<>();   // {addr, len}
    final long[] freeLists = new long[NCLASS];
    public long oldCapacity, oldLive, maxHeap;

    /// The work list for the transitive closure. An explicit stack, not
    /// recursion: a deep object graph would otherwise overflow the host stack
    /// inside the collector, which is the worst possible place for it.
    private final ArrayList<Long> work = new ArrayList<>();
    private final ArrayList<Long> rememberedDuringCollect = new ArrayList<>();

    public long minors, majors, bytesAllocated, bytesCopied, bytesPromoted, peakLive;
    public boolean collecting, oom;
    /// Force a collection at every allocation. What turns a timing-dependent
    /// fault into a deterministic one.
    /// Collect at EVERY allocation. The native runtime has had this since
    /// rooting bugs were first hunted; here it was a field nothing ever set,
    /// so the ports had the machinery and no way to switch it on.
    ///
    ///     java -Dflint.gcstress=1 ...
    ///
    /// It turns a timing-dependent rooting bug into a deterministic one that
    /// reproduces in a small program, which is the difference between finding
    /// one and bisecting a conformance suite.
    public boolean stress = System.getProperty("flint.gcstress") != null;

    public Gc(long nurseryBytes, long maxHeap) {
        this.half = Space.alignUp(Math.max(nurseryBytes, 64 * 1024), Space.PAGE);
        this.sp = new Space(maxHeap);
        this.youngBase = sp.take(half * 2);
        if (youngBase == 0) throw new IllegalStateException("flint: cannot reserve nursery");
        this.from = youngBase;
        this.to = youngBase + half;
        this.bump = youngBase;
        this.fromEnd = youngBase + half;
        this.maxHeap = maxHeap;
        addChunk(MIN_CHUNK);
    }

    public boolean isYoung(long addr) { return Long.compareUnsigned(addr - youngBase, half * 2) < 0; }

    /// Is `addr` in the half that is currently live?
    ///
    /// The other half of the stale-value check below. A young address outside
    /// `[from, bump)` is in the DEAD half: it was valid before the last
    /// collection and is not now.
    public boolean inLiveHalf(long addr) {
        return Long.compareUnsigned(addr - from, bump - from) < 0;
    }
    boolean inFrom(long addr) { return Long.compareUnsigned(addr - from, half) < 0; }
    public long youngUsed() { return bump - from; }
    public long heapUsed() { return youngUsed() + oldLive; }

    // --- old space ---------------------------------------------------------

    boolean addChunk(long want) {
        long size = Space.alignUp(Math.max(want, MIN_CHUNK), Space.PAGE);
        if (oldCapacity + size > maxHeap) return false;
        long addr = sp.take(size);
        if (addr == 0) return false;
        oldChunks.add(new long[]{addr, size});
        oldCapacity += size;
        // A free block's `len` is a byte size in a u32 field, so one block caps
        // at 4 GB even though the space no longer does. Chunks are far smaller
        // and there can be many.
        writeHeader(sp, addr, TY_FREE, (int) size);
        pushFree(addr, size);
        return true;
    }

    static int classOf(long size) {
        long c = size / 8;
        return c >= NCLASS ? NCLASS - 1 : (int) c;
    }

    void pushFree(long addr, long size) {
        writeHeader(sp, addr, TY_FREE, (int) size);
        if (size < 16) return;  // an 8-byte hole: unlinkable, coalesced by the next sweep
        int c = classOf(size);
        // A u64, because the next-pointer IS an address. The guard above is
        // what reserves the room: a linked block always has the eight bytes at
        // +8 free, which is exactly a wide pointer.
        sp.writeU64(addr + 8, freeLists[c]);
        freeLists[c] = addr;
    }

    long takeFree(long size) {
        int want = classOf(size);
        for (int c = want; c < NCLASS - 1; c++) {
            long head = freeLists[c];
            if (head != 0) {
                freeLists[c] = sp.readU64(head + 8);
                split(head, Integer.toUnsignedLong(len(sp, head)), size);
                return head;
            }
        }
        long prev = 0, cur = freeLists[NCLASS - 1];
        while (cur != 0) {
            long bs = Integer.toUnsignedLong(len(sp, cur));
            long next = sp.readU64(cur + 8);
            if (bs >= size) {
                if (prev == 0) freeLists[NCLASS - 1] = next;
                else sp.writeU64(prev + 8, next);
                split(cur, bs, size);
                return cur;
            }
            prev = cur;
            cur = next;
        }
        return 0;
    }

    void split(long addr, long block, long want) {
        long rest = block - want;
        if (rest > 0) pushFree(addr + want, rest);
    }

    long allocOld(int ty, int len) {
        long size = sizeFor(ty, len);
        long a = takeFree(size);
        if (a == 0) {
            if (!addChunk(size + Space.PAGE)) return 0;
            a = takeFree(size);
            if (a == 0) return 0;
        }
        writeHeader(sp, a, ty, len);
        setAge(sp, a, PROMOTE_AGE);
        oldLive += size;
        return a;
    }

    // --- allocation --------------------------------------------------------

    /// WOULD allocating this collect?
    ///
    /// Asked BEFORE allocating, because under several executors a collection
    /// has to be staged -- everyone stopped -- and staging one around every
    /// allocation would be a stop-the-world per allocation rather than per
    /// collection. Conservative on purpose: a false yes costs one needless
    /// safepoint, a false no would let the collector move objects while
    /// another thread was running.
    public boolean wouldCollect(int ty, int len) {
        long size = sizeFor(ty, len);
        return size >= LARGE_OBJECT || bump + size > from + half;
    }

    public long alloc(Roots roots, int ty, int len) {
        long size = sizeFor(ty, len);
        bytesAllocated += size;
        if (size >= LARGE_OBJECT) {
            long a = allocOldCollecting(roots, ty, len);
            if (a != 0) {
                zeroBody(a, ty, len);
                // A fresh old object may be given young pointers and we do not
                // know yet, so it is enrolled up front.
                remember(a, roots);
            }
            return a;
        }
        if (stress) minor(roots);
        if (bump + size > fromEnd) {
            collectCycle(roots);
            if (bump + size > fromEnd) {
                long a = allocOldCollecting(roots, ty, len);
                if (a != 0) { zeroBody(a, ty, len); remember(a, roots); }
                return a;
            }
        }
        long a = bump;
        bump += size;
        writeHeader(sp, a, ty, len);
        zeroBody(a, ty, len);
        return a;
    }

    long allocOldCollecting(Roots roots, int ty, int len) {
        long a = allocOld(ty, len);
        if (a != 0) return a;
        // Failing while there is still garbage to reclaim would make the memory
        // cap depend on when the collector last ran -- exactly the timing
        // dependence a deterministic limit exists to avoid.
        if (!collecting) { major(roots); a = allocOld(ty, len); }
        if (a == 0) oom = true;
        return a;
    }

    void collectCycle(Roots roots) {
        if (collecting) return;
        collecting = true;
        minor(roots);
        // Promotion may have filled the old space; a major reclaims it.
        if (oldLive * 2 > oldCapacity) major(roots);
        collecting = false;
    }

    void zeroBody(long a, int ty, int len) {
        switch (layoutOf(ty)) {
            case VALS -> { for (int i = 0; i < len; i++) setSlotRaw(sp, a, i, Val.NIL); }
            case STR -> sp.writeU32(a + 8, 0);
            default -> {}
        }
    }

    // --- the write barrier -------------------------------------------------

    /// Enrol `obj` if it is old. One store and a test, on the write path.
    public void remember(long obj, Roots roots) {
        if (isYoung(obj) || inRemset(sp, obj)) return;
        setInRemset(sp, obj, true);
        roots.remembered.add(obj);
    }

    /// The barrier itself: writing a young pointer into an old object.
    public void setSlot(long obj, int i, long v, Roots roots) {
        setSlotRaw(sp, obj, i, v);
        if (Val.isHeap(v) && isYoung(Val.asHeap(v))) remember(obj, roots);
    }

    // --- the copying nursery collection ------------------------------------

    long forward(long v) {
        if (!Val.isHeap(v)) return v;
        long a = Val.asHeap(v);
        if (!inFrom(a)) return v;
        if (ty(sp, a) == TY_FWD) return Val.heap(forwardTarget(sp, a));

        long size = sizeOf(sp, a);
        int newAge = age(sp, a) + 1;
        long dest = 0;
        if (newAge >= PROMOTE_AGE) {
            long d = allocOld(ty(sp, a), len(sp, a));
            if (d != 0) { bytesPromoted += size; dest = d; }
            // Out of old space: keep it young; the next major tries again.
        }
        if (dest != 0) {
            sp.copyWithin(a, dest, size);
            setAge(sp, dest, newAge);
            setInRemset(sp, dest, false);
            setMarked(sp, dest, false);
        } else {
            dest = toBump;
            if (dest + size > to + half) {
                // TO-SPACE OVERFLOW. The Rust asserts this in a debug build and
                // relies on promotion to keep it impossible; without the check
                // the copy runs off the end of the semispace and into the other
                // one, and the symptom is a CYCLE in a list several thousand
                // objects later, nowhere near the cause.
                throw new IllegalStateException(
                    "to-space overflow: " + (dest + size) + " past " + (to + half)
                    + " (promotion is not draining the nursery)");
            }
            toBump += size;
            sp.copyWithin(a, dest, size);
            setAge(sp, dest, Math.min(newAge, PROMOTE_AGE - 1));
            bytesCopied += size;
        }
        setForward(sp, a, dest);
        work.add(dest);
        return Val.heap(dest);
    }

    void scanObject(long a) {
        int t = ty(sp, a);
        if (layoutOf(t) != VALS) return;
        int n = len(sp, a);
        boolean old = !isYoung(a);
        boolean pointsYoung = false;
        for (int i = 0; i < n; i++) {
            long v = slot(sp, a, i);
            if (!Val.isHeap(v)) continue;
            long nv = forward(v);
            if (nv != v) setSlotRaw(sp, a, i, nv);
            if (isYoung(Val.asHeap(nv))) pointsYoung = true;
        }
        // AN OLD OBJECT THAT STILL POINTS YOUNG GOES BACK IN THE SET.
        //
        // Leaving this out is what a lost generational invariant looks like:
        // the edge is forgotten, the young object is never traced by the next
        // collection, it dies while still referenced, and its address is
        // reused. The symptom is not a crash -- it is a LIST THAT LOOPS, ten
        // thousand objects from the cause, because a reused address makes the
        // chain appear to rejoin itself.
        //
        // Inside a collection nothing else is running, so this goes straight
        // into the set being rebuilt rather than through the write barrier.
        if (old && pointsYoung) {
            setInRemset(sp, a, true);
            rememberedDuringCollect.add(a);
        }
    }

    public void minor(Roots roots) {
        minors++;
        toBump = to;
        work.clear();

        // 1. roots
        roots.forEach(this::forward);

        // 2. the remembered set: old -> young edges
        // EVERY executor's, not just this one's. Draining one and not the rest
        // would lose old-to-young edges another thread recorded, and a lost
        // edge is a young object collected while an old one still points at it.
        ArrayList<Long> oldRem = roots.drainRemembered();
        for (long a : oldRem) setInRemset(sp, a, false);
        for (long a : oldRem) scanObject(a);

        // 3. transitive closure
        while (!work.isEmpty()) scanObject(work.remove(work.size() - 1));

        // 4. weak tables. A nursery entry that was copied is FORWARDED; one
        // that was not is dead, and the entry goes. This runs BEFORE the flip,
        // while `from` still names the space that was just evacuated.
        for (Interns tbl : roots.shared.interns) {
            tbl.refresh(v -> {
                if (Val.isHeap(v) && Long.compareUnsigned(Val.asHeap(v) - from, half) < 0) {
                    long a2 = Val.asHeap(v);
                    return ty(sp, a2) == TY_FWD ? Val.heap(forwardTarget(sp, a2)) : Val.NOT_FOUND;
                }
                return v;
            });
        }

        // 5. flip
        long t = from; from = to; to = t;
        bump = toBump;
        fromEnd = from + half;
        notePeak();

        // Survivors that pointed at young objects go back on a mutator's list.
        roots.remembered.addAll(rememberedDuringCollect);
        rememberedDuringCollect.clear();
    }

    // --- the old-space collection ------------------------------------------

    public void major(Roots roots) {
        minor(roots);
        majors++;
        work.clear();
        roots.forEach(v -> { markFrom(v); return v; });
        while (!work.isEmpty()) {
            long a = work.remove(work.size() - 1);
            if (layoutOf(ty(sp, a)) != VALS) continue;
            int n = len(sp, a);
            for (int i = 0; i < n; i++) markFrom(slot(sp, a, i));
        }
        // Weak tables: anything unmarked is unreachable.
        for (Interns tbl : roots.shared.interns) {
            tbl.refresh(v -> (!Val.isHeap(v) || marked(sp, Val.asHeap(v))) ? v : Val.NOT_FOUND);
        }

        // The remembered set may name objects about to be freed.
        ArrayList<Long> rem = roots.drainRemembered();
        for (long a : rem) {
            if (marked(sp, a)) rememberedDuringCollect.add(a);
            else setInRemset(sp, a, false);
        }
        sweepOld();
        notePeak();
        // Clear marks on the live nursery.
        long a = from;
        while (a < bump) { setMarked(sp, a, false); a += sizeOf(sp, a); }
        if (oldLive * 2 > oldCapacity) addChunk(oldLive * 2 - oldCapacity);
        roots.remembered.addAll(rememberedDuringCollect);
        rememberedDuringCollect.clear();
    }

    void markFrom(long v) {
        if (!Val.isHeap(v)) return;
        long a = Val.asHeap(v);
        if (marked(sp, a)) return;
        setMarked(sp, a, true);
        work.add(a);
    }

    void sweepOld() {
        java.util.Arrays.fill(freeLists, 0);
        long live = 0;
        for (long[] ch : oldChunks) {
            long a = ch[0], end = ch[0] + ch[1];
            long runStart = 0;
            while (a < end) {
                long size = sizeOf(sp, a);
                if (size == 0) break;
                int t = ty(sp, a);
                boolean dead = t == TY_FREE || !marked(sp, a);
                if (dead) {
                    if (runStart == 0) runStart = a;
                } else {
                    if (runStart != 0) { pushFree(runStart, a - runStart); runStart = 0; }
                    setMarked(sp, a, false);
                    live += size;
                }
                a += size;
            }
            if (runStart != 0) pushFree(runStart, end - runStart);
        }
        oldLive = live;
    }

    void notePeak() { peakLive = Math.max(peakLive, heapUsed()); }
}
