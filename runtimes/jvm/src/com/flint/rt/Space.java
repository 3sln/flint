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

    private final Arena arena;
    private final MemorySegment mem;

    /// How much of the space has been handed out, and how much there is.
    /// `Addr`-wide, because a space can exceed 4 GB now.
    public long inUse;
    public long reserved;

    public Space(long bytes) {
        this.arena = Arena.ofShared();
        this.mem = arena.allocate(bytes, 8);
        this.reserved = bytes;
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
        long a = inUse;
        inUse += len;
        return a;
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
    // and publish without a lock (`doc/decisions/0028`). Nothing else in the
    // heap is written by two threads at once -- a collection is stop-the-world
    // at a safepoint -- so ordinary slots need no synchronisation.
    private static final java.lang.invoke.VarHandle I64_ATOMIC =
        ValueLayout.JAVA_LONG.varHandle();

    public long atomicLoad(long addr) {
        return (long) I64_ATOMIC.getVolatile(mem, addr);
    }

    public void atomicStore(long addr, long v) {
        I64_ATOMIC.setVolatile(mem, addr, v);
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
