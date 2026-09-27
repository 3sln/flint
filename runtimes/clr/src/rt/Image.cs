namespace Flint.Rt;

using System;
using System.Reflection;

/// A COMPILED FLINT PROGRAM, AS A VALUE.
///
/// The CLR's mirror of `com.flint.Image` on the JVM and `new Image(wasmBytes)` in
/// the ESM SDK. Each takes an artifact as BYTES and hands back something you can
/// boot. The CLR had no such type: `runtimes/clr/artifact/Check.cs` reached an
/// artifact with `Assembly.LoadFrom(path)` -- a FILE PATH -- and then resolved
/// `Program`, `Boot`, `Link` and the metadata attribute inline, every time.
///
/// `Assembly.Load(byte[])` is the CLR's `defineClass`, so an artifact never had to
/// be a file here either.
///
/// ## Where this DIFFERS from the JVM's, and why that is not an oversight
///
/// The JVM's `Image` must hold the class bytes, because its metadata is a raw
/// class-file attribute read by hand -- and recovering the bytes from a class
/// defined from bytes returns NULL, so a byte-loaded artifact had metadata that
/// was present and unreadable. The CLR's metadata is a real
/// `_3sln.Flint.MetaAttribute`, read by REFLECTION off the assembly, which works
/// for a byte-loaded assembly exactly as it does for a file. So `Metadata()` here
/// needs nothing kept.
///
/// The bytes are kept anyway, for `Bytes()`: a caller that compiled in memory and
/// now wants a file should not have to compile again. That is uniformity with the
/// other SDKs rather than necessity, and saying which is which is the point.
public sealed class Image {
    readonly byte[] bytes;                 // null for the path route below
    readonly Assembly asm;
    readonly Type ty;

    Image(byte[] bytes, Assembly asm) {
        this.bytes = bytes;
        this.asm = asm;
        // THE ARTIFACT'S TYPE IS `Program` (`src/flint/clr.cljc`). Refused by name
        // rather than answering a null type: an assembly that is not a flint
        // artifact is a caller's mistake, and `NullReferenceException` three calls
        // later names nothing.
        this.ty = asm.GetType("Program")
            ?? throw new ArgumentException(
                "no `Program` type: that type IS the compiled program, and "
                + "`flint compile :to :clr` emits it. This assembly is something else.");
    }

    /// FROM BYTES, which is the ordinary way and what the other SDKs have. No
    /// file, no path, no assembly resolution.
    public static Image Of(byte[] assemblyBytes) {
        if (assemblyBytes == null || assemblyBytes.Length < 2
            || assemblyBytes[0] != 0x4d || assemblyBytes[1] != 0x5a) {
            // `MZ` OPENS EVERY PE, and the compiler answers with a string either
            // way -- so a diagnostic handed to `Assembly.Load` fails deep in the
            // loader with nothing naming the caller's mistake. The native CLI
            // sniffs for the same two bytes for the same reason.
            throw new ArgumentException(
                "not a PE assembly: a compiled flint artifact starts with `MZ`. "
                + "`flint compile :to :clr` emits these bytes.");
        }
        return new Image(assemblyBytes, Assembly.Load(assemblyBytes));
    }

    /// FROM A FILE, for a consumer who has one on disk.
    public static Image OnPath(string path) {
        return new Image(null, Assembly.LoadFrom(path));
    }

    /// Arbitrary metadata the compiler recorded, as the EDN string the attribute
    /// holds, or null when the artifact carries none.
    ///
    /// NOT PARSED, for `com.flint.Image`'s reason: EDN is not this platform's
    /// native shape, and an EDN reader in the runtime would be a parser nobody
    /// asked for.
    public string Metadata() {
        return asm.GetCustomAttribute<_3sln.Flint.MetaAttribute>()?.Edn;
    }

    /// The artifact bytes, or null for an image loaded from a path.
    public byte[] Bytes() { return bytes; }

    public Type Type_() { return ty; }

    /// The flint IMAGE the artifact carries -- its bytecode, as constant data on
    /// the type (`DECISIONS.md#four-operations`).
    public byte[] Bytecode() {
        return (byte[]) ty.GetMethod("Image").Invoke(null, null);
    }

    /// Boot it. Answers the `Artifact` whose `Loop` pumps it -- an INSTANCE,
    /// because a static one is one sandbox per load context.
    public Artifact Boot(Artifact.IBridge system) {
        return (Artifact) ty.GetMethod("Boot").Invoke(null, new object[]{ system });
    }

    /// Supply a native. Must precede `Boot` (`DECISIONS.md#four-operations`).
    public void Link(Artifact.IBridge bridge, string name, object fn) {
        ty.GetMethod("Link").Invoke(null, new object[]{ bridge, name, fn });
    }
}
