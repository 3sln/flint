package com.flint.rt;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/// Raw memory: one flat, byte-addressed space the whole runtime lives inside.
/// Ported from `runtime/src/mem.rs`.
///
/// ## Why off-heap, and not a `byte[]` or a `long[]`
///
/// A `byte[]` caps at 2 GB (the index is an `int`) and forces byte-at-a-time
/// access. A `long[]` reaches ~17 GB but lives on the Java heap: it counts
/// against `-Xmx`, needs one contiguous allocation, and G1 treats it as a
/// humongous object it must then work around.
///
/// A `MemorySegment` from the FFM API (final in JDK 22) has none of that. It is
/// `long`-addressed, off-heap, invisible to the host collector, and its
/// accessors are JIT intrinsics. It is also the exact analogue of wasm's linear
/// memory, which is the point: this file is the ONLY one in the port that
/// differs from the Rust in more than syntax, because it is the only one that
/// touches the platform.
///
/// ## It starts small and grows (`DECISIONS.md#growable-heap`)
///
/// `Arena.allocate` ZERO-FILLS EAGERLY on OpenJDK 25: backing a 2 GB ceiling
/// up front took 2 240 ms and 2.19 GB resident before the first instruction
/// (measured 2026-10-05, `/usr/bin/time -l` over a five-line probe). So the
/// segment covers only `committed` bytes, sized by the shared policy in
/// `kin/heapgrow.kin`, and `take` grows it -- a bigger segment, the used
/// prefix copied, the old arena closed. The CEILING is still `reserved` and
/// `take` still refuses past it first, so the catchable limit is unchanged.
///
/// Moving the segment is safe for the reason a moving collection is: `mem` is
/// private, every access goes through it, and `take` only runs inside a
/// collection or a large allocation -- the two that `gcWouldCollect` stages
/// with every other executor stopped.
///
/// ## Lifetime is ours
///
/// An `Arena` owns the mapping, and closing it frees the heap immediately
/// rather than whenever the host collector gets round to it. That is the right
/// shape for a sandbox: dropping one should release its memory now.
public final class Space implements AutoCloseable {
    /// Unaligned layouts throughout. The Rust reads with `read_unaligned`, and
    /// an object header is 8-byte aligned while a string's bytes are not.
    private static final ValueLayout.OfLong I64 = ValueLayout.JAVA_LONG_UNALIGNED;
    private static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT_UNALIGNED;
    private static final ValueLayout.OfByte I8 = ValueLayout.JAVA_BYTE;

    public static final long PAGE = 65536;

    private Arena arena;
    private MemorySegment mem;

    /// How much of the space has been handed out, and the ceiling it may
    /// never pass. `Addr`-wide, because a space can exceed 4 GB now.
    public long inUse;
    public long reserved;
    /// How much of `reserved` the segment actually covers.
    public long committed;

    public Space(long bytes) {
        // LITTLE-ENDIAN, PINNED -- see the Rust runtime's `lib.rs`. The heap
        // is native order and a live set copies raw bodies verbatim, so a
        // big-endian JVM would read every other runtime's export wrong.
        if (java.nio.ByteOrder.nativeOrder() != java.nio.ByteOrder.LITTLE_ENDIAN)
            throw new IllegalStateException("flint's heap and live-set format are little-endian; this JVM is not");
        this.reserved = bytes;
        this.committed = com._3sln.flint.kgen.rt.Heapgrow.spaceInitial(bytes);
        this.arena = Arena.ofShared();
        this.mem = arena.allocate(committed, 8);
        // Address 0 is never a valid object, so nothing is handed out from it.
        this.inUse = PAGE;
    }

    @Override public void close() { arena.close(); }

    public static long alignUp(long n, long a) { return (n + a - 1) & ~(a - 1); }

    /// Hand out `len` bytes. 0 means the space is full -- never an exception,
    /// because the caller turns it into a catchable flint error and a Java
    /// exception here would unwind past the frame that knows what failed.
    public long take(long len) {
        len = alignUp(len, PAGE);
        if (inUse + len > reserved) return 0;
        if (!ensure(inUse + len)) return 0;
        long a = inUse;
        inUse += len;
        return a;
    }

    /// Back every byte below `end`. False past the ceiling, or when the host
    /// cannot find the bytes -- which the caller reports as the same
    /// catchable limit a full space always was.
    public boolean ensure(long end) {
        if (end > reserved) return false;
        if (end <= committed) return true;
        long want = com._3sln.flint.kgen.rt.Heapgrow.spaceGrowTo(committed, end, reserved);
        Arena next = Arena.ofShared();
        MemorySegment grown;
        try {
            grown = next.allocate(want, 8);
        } catch (OutOfMemoryError e) {
            next.close();
            return false;
        }
        // The USED prefix, not the whole old segment: above `inUse` nothing
        // has been handed out, and the new segment is already zero there.
        MemorySegment.copy(mem, 0, grown, 0, Math.min(inUse, committed));
        arena.close();
        arena = next;
        mem = grown;
        committed = want;
        return true;
    }

    public int readU32(long addr) { return mem.get(I32, addr); }
    public void writeU32(long addr, int v) { mem.set(I32, addr, v); }
    public long readU64(long addr) { return mem.get(I64, addr); }
    public void writeU64(long addr, long v) { mem.set(I64, addr, v); }

    // --- atomic word access -------------------------------------------------
    //
    // Mirrors the Rust `Space::atomic_load` / `atomic_store` / `cas`. Every
    // object is 8-aligned -- `sizeFor` rounds each layout to a multiple of 8 --
    // so `slotAddr`, which is `base + 8 + i * 8`, is always aligned and these
    // are legal. A `VarHandle` over a MemorySegment supports the atomic access
    // modes only for aligned addresses, which is the same requirement.
    //
    // These exist for ONE thing: a port's inbox, where two executors reserve
    // and publish without a lock (`DECISIONS.md#drivers`). Nothing else in the
    // heap is written by two threads at once -- a collection is stop-the-world
    // at a safepoint -- so ordinary slots need no synchronisation.
    private static final java.lang.invoke.VarHandle I64_ATOMIC =
        ValueLayout.JAVA_LONG.varHandle();

    public long atomicLoad(long addr) {
        return (long) I64_ATOMIC.getVolatile(mem, addr);
    }

    /// Compare-and-swap. True when this thread won the slot.
    public boolean cas(long addr, long want, long next) {
        return I64_ATOMIC.compareAndSet(mem, addr, want, next);
    }
    public int readU8(long addr) { return mem.get(I8, addr) & 0xFF; }
    public void writeU8(long addr, int v) { mem.set(I8, addr, (byte) v); }

    public byte[] bytes(long addr, int len) {
        byte[] out = new byte[len];
        MemorySegment.copy(mem, I8, addr, out, 0, len);
        return out;
    }

    public void writeBytes(long addr, byte[] src) {
        MemorySegment.copy(src, 0, mem, I8, addr, src.length);
    }

    public void copyWithin(long from, long to, long len) {
        MemorySegment.copy(mem, from, mem, to, len);
    }

    public void zero(long addr, long len) {
        mem.asSlice(addr, len).fill((byte) 0);
    }
}
