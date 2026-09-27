package com.flint.rt;

/// A COMPILED FLINT PROGRAM, LOADED FROM BYTES.
///
/// The mirror of the CLR's `Img.Load(rt, bytes)` and the ESM SDK's
/// `new Image(wasmBytes)`: an artifact arrives as BYTES and is loaded in memory.
/// Before this existed, the JVM was the only runtime that loaded one by NAME --
/// `Class.forName("flint.Artifact")` -- which drags the classpath, and therefore
/// the filesystem, into the loading mechanism.
///
/// THAT IS WHERE `:to :jvm :out <directory>` CAME FROM. A JVM loads a class only
/// from a path matching the class's own name, so a fixed name `flint.Artifact`
/// forced `:out` to be the classpath ROOT that `flint/Artifact.class` hangs off,
/// while every other target takes a file. The name was never the requirement; the
/// requirement was an artifact that only exists as a file. Loaded from bytes, the
/// name is arbitrary and the question does not arise.
///
/// NOTHING NEW IS INVENTED HERE. `AotEmit` already defines classes in memory this
/// way for compiled arities (`MethodHandles.lookup().defineClass`), and `Img`
/// already loads a flint image from bytes. This is those two facts applied to the
/// artifact itself.
public final class Artifact {
    private Artifact() {}

    /// A class loader per artifact, so two artifacts can define classes of the
    /// SAME NAME in one process -- which is the JVM's answer to instantiating a
    /// module twice, and the reason `Sandbox` documents taking a second loader.
    static final class Defining extends ClassLoader {
        Defining(ClassLoader parent) { super(parent); }
        Class<?> define(byte[] b) { return defineClass(null, b, 0, b.length); }
    }

    /// Define `classBytes` and hand back the class. The bytes are what
    /// `flint compile :to :jvm` emits, whether or not they were ever written to a
    /// file.
    ///
    /// The name is READ OUT OF THE BYTES by the JVM itself, so a caller neither
    /// supplies nor needs one.
    public static Class<?> define(byte[] classBytes) {
        if (classBytes == null || classBytes.length < 4
            || (classBytes[0] & 0xFF) != 0xCA || (classBytes[1] & 0xFF) != 0xFE
            || (classBytes[2] & 0xFF) != 0xBA || (classBytes[3] & 0xFF) != 0xBE) {
            // REFUSED BY NAME rather than read as a plausible class, the way
            // `Img.load` refuses a non-image: a wrong artifact handed to
            // `defineClass` fails deep inside the JVM with nothing naming the
            // caller's mistake.
            throw new IllegalArgumentException(
                "not a class file: a compiled flint artifact starts with 0xCAFEBABE."
                + " `flint compile :to :jvm` emits these bytes.");
        }
        return new Defining(Artifact.class.getClassLoader()).define(classBytes);
    }
}
