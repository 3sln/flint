using System.Runtime.InteropServices;

namespace Flint.Rt;

/// Raw memory: one flat, byte-addressed space the whole runtime lives inside.
/// Ported from `runtime/src/mem.rs`.
///
/// ## Why unmanaged, and not a byte[] or long[]
///
/// A `byte[]` caps at 2 GB and forces byte-at-a-time access; a `long[]` reaches
/// ~17 GB but lives on the managed heap, counting against the GC and needing
/// one contiguous allocation.
///
/// `NativeMemory` has neither problem, and .NET is the better of the two hosts
/// here: `unsafe` gives real pointer arithmetic with no bounds check at all,
/// where the JVM's `MemorySegment` keeps one the JIT has to hoist. This is the
/// exact analogue of wasm's linear memory, which is the point -- it is the only
/// file in the port that differs from the Rust in more than syntax, because it
/// is the only one that touches the platform.
public sealed unsafe class Space : System.IDisposable {
    public const long Page = 65536;

    private byte* _base;

    /// How much has been handed out, and how much there is. `Addr`-wide,
    /// because a space can exceed 4 GB.
    public long InUse;
    public long Reserved;

    public Space(long bytes) {
        _base = (byte*) NativeMemory.AllocZeroed((nuint) bytes);
        if (_base == null) throw new System.OutOfMemoryException("flint: could not reserve the heap");
        Reserved = bytes;
        // Address 0 is never a valid object.
        InUse = Page;
    }

    public void Dispose() {
        if (_base != null) { NativeMemory.Free(_base); _base = null; }
    }

    public static long AlignUp(long n, long a) => (n + a - 1) & ~(a - 1);

    /// Hand out `len` bytes. 0 means full -- never an exception, because the
    /// caller turns it into a catchable flint error.
    public long Take(long len) {
        len = AlignUp(len, Page);
        if (InUse + len > Reserved) return 0;
        long a = InUse;
        InUse += len;
        return a;
    }

    public int ReadU32(long addr) => *(int*)(_base + addr);
    public void WriteU32(long addr, int v) => *(int*)(_base + addr) = v;
    public long ReadU64(long addr) => *(long*)(_base + addr);
    public void WriteU64(long addr, long v) => *(long*)(_base + addr) = v;

    // --- atomic word access -------------------------------------------------
    //
    // Mirrors the Rust `Space::atomic_load` / `cas`. There was an
    // `atomic_store` in all three and nothing ever called it: publication
    // goes through `cas`, which also runs the WRITE BARRIER, so a bare
    // atomic store to a slot would skip it. Removed 2026-09-23. Every
    // object is 8-aligned -- `SizeFor` rounds each layout to a multiple of 8 --
    // so `SlotAddr`, which is `base + 8 + i * 8`, is always aligned, which is
    // what makes an interlocked operation on it legal.
    //
    // These exist for ONE thing: a port's inbox, where two executors reserve
    // and publish without a lock (`DECISIONS.md#drivers`). Nothing else in the
    // heap is written by two threads at once -- a collection is stop-the-world
    // at a safepoint -- so ordinary slots need no synchronisation.
    public long AtomicLoad(long addr) =>
        System.Threading.Volatile.Read(ref *(long*)(_base + addr));

    /// Compare-and-swap. True when this thread won the slot.
    public bool Cas(long addr, long want, long next) =>
        System.Threading.Interlocked.CompareExchange(
            ref *(long*)(_base + addr), next, want) == want;
    public int ReadU8(long addr) => _base[addr];
    public void WriteU8(long addr, int v) => _base[addr] = (byte) v;

    public byte[] Bytes(long addr, int len) {
        var out_ = new byte[len];
        Marshal.Copy((System.IntPtr)(_base + addr), out_, 0, len);
        return out_;
    }

    public void WriteBytes(long addr, byte[] src) =>
        Marshal.Copy(src, 0, (System.IntPtr)(_base + addr), src.Length);

    public void CopyWithin(long from, long to, long len) =>
        System.Buffer.MemoryCopy(_base + from, _base + to, len, len);

    public void Zero(long addr, long len) =>
        NativeMemory.Clear(_base + addr, (nuint) len);
}
