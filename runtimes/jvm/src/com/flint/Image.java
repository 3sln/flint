package com.flint;

import com.flint.rt.Builtins;
import com.flint.rt.Sandbox;
import java.lang.reflect.Method;

/// A COMPILED FLINT PROGRAM, AS A VALUE.
///
/// The JVM's mirror of the ESM SDK's `Image`. That one is
/// `new Image(wasmBytes)` with `.metadata` and `.sandbox()`; the CLR's is
/// `Img.Load(rt, bytes)`. Each takes an artifact as BYTES and hands back
/// something you can boot. The JVM had no such thing: a host reached the
/// artifact by `Class.forName("flint.Artifact")` and re-derived the three
/// methods reflectively every time, which `com.flint.Main` and
/// `com.flint.FourOps` each did privately.
///
/// ## Why it holds the bytes
///
/// The metadata is a class ATTRIBUTE, read out of the class file rather than by
/// calling the artifact (`DECISIONS.md#four-operations`). Recovering those bytes
/// through `getClassLoader().getResourceAsStream(...)` works for a class on a
/// classpath and returns NULL for one defined from bytes -- so a byte-loaded
/// artifact had metadata that was present in the file and unreadable through the
/// API. Measured: `resource for a byte-defined class: NULL`, while the same bytes
/// yield the attribute directly.
///
/// Holding the bytes makes `metadata()` work for BOTH routes and needs no class
/// loader at all, which is what the metadata decision wanted in the first place.
public final class Image {
    private final byte[] bytes;          // null only for the classpath route below
    private final Class<?> clazz;
    private final Method bootM, loopM, linkM;

    private Image(byte[] bytes, Class<?> k) throws Exception {
        this.bytes = bytes;
        this.clazz = k;
        // THE THREE OPERATIONS, resolved once (`DECISIONS.md#four-operations`).
        this.bootM = k.getMethod("boot", Sandbox.Bridge.class);
        this.loopM = k.getMethod("loop");
        this.linkM = k.getMethod("link", String.class, Builtins.Fn.class);
    }

    /// FROM BYTES, which is the ordinary way and the one the other SDKs have.
    /// The class is defined in memory: no classpath, no file, and the name is read
    /// out of the bytes by the JVM rather than agreed with a path.
    public static Image of(byte[] classBytes) throws Exception {
        return new Image(classBytes, com.flint.rt.Artifact.define(classBytes));
    }

    /// FROM A CLASSPATH, for a consumer who put the artifact on one. The bytes are
    /// recovered so `metadata()` behaves the same either way; if the loader will
    /// not give them up, metadata is null rather than wrong.
    public static Image onClasspath(String name) throws Exception {
        Class<?> k = Class.forName(name);
        byte[] b = null;
        try (java.io.InputStream in = k.getClassLoader()
                 .getResourceAsStream(k.getName().replace('.', '/') + ".class")) {
            if (in != null) b = in.readAllBytes();
        } catch (Exception ignored) { /* metadata stays null; the ops still work */ }
        return new Image(b, k);
    }

    /// The default name `:to :jvm` emits when `:out` is a classpath root.
    public static Image onClasspath() throws Exception {
        return onClasspath("flint.Artifact");
    }

    /// Arbitrary metadata the compiler recorded, as the EDN string the attribute
    /// holds, or null when the bytes were not available.
    ///
    /// NOT PARSED HERE. The ESM SDK hands back an object because JSON is its
    /// native shape; EDN is not the JVM's, and an EDN reader in the runtime would
    /// be a parser nobody asked for. A caller that wants fields reads them with
    /// whatever it already has.
    public String metadata() {
        return bytes == null ? null : ClassAttr.read(bytes, "com.3sln.flint.meta");
    }

    /// The artifact bytes, or null for a classpath image whose loader withheld
    /// them. Writing one out is a caller's business: an artifact does not have to
    /// be a file to be usable, which is the point of `of`.
    public byte[] bytes() { return bytes; }

    public Class<?> clazz() { return clazz; }

    /// Boot it. The mirror of the ESM SDK's `image.sandbox()`.
    public Sandbox boot(Sandbox.Bridge system) throws Exception {
        return (Sandbox) bootM.invoke(null, system);
    }

    /// Pump it. Answers one of `Sandbox.DONE THREW NEEDS_HOST SHELVED WEDGED`.
    public int loop() throws Exception {
        return (int) loopM.invoke(null);
    }

    /// Supply a native. Must precede `boot` (`DECISIONS.md#four-operations`).
    public void link(String name, Builtins.Fn fn) throws Exception {
        linkM.invoke(null, name, fn);
    }
}
