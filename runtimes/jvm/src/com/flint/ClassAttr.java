package com.flint;

import java.nio.charset.StandardCharsets;

/// READ ONE CLASS-LEVEL ATTRIBUTE OUT OF CLASS-FILE BYTES.
///
/// This is where an artifact's metadata lives (`DECISIONS.md#four-operations`): a
/// class attribute named `com.3sln.flint.meta`, the same string the wasm custom
/// section uses. JVMS 4.7 REQUIRES a reader to silently ignore an attribute it does
/// not recognise, which is what makes a custom one legal and inert -- and also what
/// makes the namespace load-bearing, because a colliding name is not an error, it is
/// wrong data read as right.
///
/// NO JVM AND NO CLASS LOADING. That is the whole reason the metadata is here
/// rather than behind the `prop` operation that used to exist: a reader with the
/// bytes has the answer, and `javap`, a build tool or `flint inspect` can get it
/// without flint's runtime on its path. `getAnnotation` would have been one line and
/// needs the class loaded and the annotation type present.
///
/// IT IS IN `com.flint`, NOT `com.flint.rt`, because it is not runtime machinery:
/// nothing the interpreter does reads it. A host does.
public final class ClassAttr {
    private ClassAttr() {}

    /// The named attribute's payload as UTF-8 text, or null if the class has no
    /// such attribute.
    ///
    /// Walking here means stepping over the constant pool, and the constant pool is
    /// the only hard part: entries are variable width, and `CONSTANT_Long` and
    /// `CONSTANT_Double` TAKE TWO SLOTS each. A reader that advances by one on those
    /// is off by one for every entry after them and reads a length field out of the
    /// middle of a string -- which is why that is the one line here with a comment
    /// of its own.
    public static String read(byte[] b, String name) {
        int at = 8;                                     // magic, minor, major
        int poolCount = u2(b, at); at += 2;
        String[] utf8 = new String[poolCount];
        for (int i = 1; i < poolCount; i++) {
            int tag = b[at++] & 0xff;
            switch (tag) {
                case 1 -> {                             // CONSTANT_Utf8
                    int n = u2(b, at); at += 2;
                    utf8[i] = new String(b, at, n, StandardCharsets.UTF_8);
                    at += n;
                }
                case 7, 8, 16, 19, 20 -> at += 2;       // Class, String, MethodType, Module, Package
                case 15 -> at += 3;                     // MethodHandle
                case 3, 4, 9, 10, 11, 12, 17, 18 -> at += 4;
                // TWO SLOTS. JVMS 4.4.5: "all 8-byte constants take up two entries",
                // and the second is unusable. Skipping it is not an optimisation, it
                // is the format.
                case 5, 6 -> { at += 8; i++; }
                default -> throw new IllegalArgumentException(
                    "constant pool entry " + i + " has unknown tag " + tag);
            }
        }
        at += 2 + 2 + 2;                                // access, this, super
        at += 2 + 2 * u2(b, at);                        // interfaces
        at = skipMembers(b, at, utf8);                  // fields
        at = skipMembers(b, at, utf8);                  // methods
        int attrs = u2(b, at); at += 2;
        for (int i = 0; i < attrs; i++) {
            String an = utf8[u2(b, at)]; at += 2;
            long len = u4(b, at); at += 4;
            if (name.equals(an)) return new String(b, at, (int) len, StandardCharsets.UTF_8);
            at += len;
        }
        return null;
    }

    /// Fields and methods have the same shape, and both carry attributes of their
    /// own that have to be stepped over to reach the class's.
    private static int skipMembers(byte[] b, int at, String[] utf8) {
        int n = u2(b, at); at += 2;
        for (int i = 0; i < n; i++) {
            at += 6;                                    // access, name, descriptor
            int attrs = u2(b, at); at += 2;
            for (int k = 0; k < attrs; k++) {
                at += 2;                                // name index
                at += 4 + (int) u4(b, at);              // length, then the payload
            }
        }
        return at;
    }

    private static int u2(byte[] b, int at) {
        return ((b[at] & 0xff) << 8) | (b[at + 1] & 0xff);
    }

    private static long u4(byte[] b, int at) {
        return ((long) (b[at] & 0xff) << 24) | ((b[at + 1] & 0xff) << 16)
             | ((b[at + 2] & 0xff) << 8) | (b[at + 3] & 0xff);
    }
}
