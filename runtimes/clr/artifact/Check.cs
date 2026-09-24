using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using System.Reflection.Metadata;
using System.Reflection.Metadata.Ecma335;
using System.Reflection.PortableExecutable;
using System.Text;
using Flint.Rt;

/// Checks one artifact emitted by `:to :clr`. Driven by `bin/check-clr`.
///
/// usage: Check &lt;assembly&gt; &lt;ns/fn&gt; &lt;expected answer&gt;
///
/// COMMITTED, not a scratch script. This began as a one-off in a scratch
/// directory and the figures it printed were the only evidence the target
/// worked -- which is the shape this project has a note about: a method left in
/// prose gets reimplemented, with the warned-about bugs plus new ones. So the
/// harness is the artifact, and `bin/check-clr` runs it.
///
/// ONE LOAD, FOUR QUESTIONS. The loader, the witness methods, the metadata and
/// the program all want the same assembly in the same process, so they share a
/// run rather than each paying for their own.
public static class Check {

    sealed class Bridge : Artifact.IBridge {
        public readonly Queue<Artifact.Msg> Out = new();
        public readonly List<Artifact.Msg> In = new();
        public bool TryTake(out Artifact.Msg m) {
            if (Out.Count == 0) { m = default; return false; }
            m = Out.Dequeue();
            return true;
        }
        public void Put(Artifact.Msg m) => In.Add(m);
        public long Open(long token, byte[] request) => -1;
        public byte[] Answer(long token, byte[] request) => null;
        public void Closed(long port) { }
    }

    /// The wire writer a host needs. Wire-encoded values are what the bridge
    /// carries, which is why `link`'s hook shape is bytes in, bytes out.
    sealed class W {
        readonly MemoryStream b = new();
        public W Tag(int x) { b.WriteByte((byte) x); return this; }
        public W U32(long v) { for (int i = 0; i < 4; i++) b.WriteByte((byte)((v >> (8*i)) & 0xff)); return this; }
        public W I64(long v) { for (int i = 0; i < 8; i++) b.WriteByte((byte)((v >> (8*i)) & 0xff)); return this; }
        public W Text(string s) { var u = Encoding.UTF8.GetBytes(s); U32(u.Length); b.Write(u, 0, u.Length); return this; }
        public W Kw(string n) => Tag(Codec.K_KEYWORD).U32(0xffffffffL).Text(n);
        public W Str(string s) => Tag(Codec.K_STRING).Text(s);
        public W Num(long n) => Tag(Codec.K_INT).I64(n);
        public W Port(int id) => Tag(Codec.K_PORT).U32(id);
        public W Map(int n) => Tag(Codec.K_MAP).U32(n);
        public W Vec(int n) => Tag(Codec.K_VECTOR).U32(n);
        public byte[] Done() => b.ToArray();
    }

    const int CALL_PORT = 2;
    static int fails;

    static void Ok(string what) => Console.WriteLine("ok   " + what);
    static void Fail(string what) { fails++; Console.WriteLine("FAIL " + what); }
    static void Want(bool cond, string what) { if (cond) Ok(what); else Fail(what); }

    public static int Main(string[] argv) {
        if (argv.Length < 2) {
            Console.Error.WriteLine("usage: Check <assembly> <ns/fn> [expected]");
            return 2;
        }
        string path = Path.GetFullPath(argv[0]), fn = argv[1];
        string expected = argv.Length > 2 ? argv[2] : "";

        // --- the metadata tables, read straight off disk. Before anything is
        // loaded, because a malformed table should be named as such rather than
        // as "the assembly would not load".
        int imageLen;
        using (var fs = File.OpenRead(path)) {
            using var pe = new PEReader(fs);
            var md = pe.GetMetadataReader();
            Want(pe.PEHeaders.SectionHeaders.Length == 1,
                 $"one section, no .reloc and no import table ({pe.PEHeaders.SectionHeaders.Length})");
            Want((int) pe.PEHeaders.CorHeader.Flags == 1, "CorFlags is ILONLY");
            Want(md.GetHeapSize(HeapIndex.Blob) < 0x10000,
                 $"the blob heap stays under 64 KB, so every index stays 2 bytes ({md.GetHeapSize(HeapIndex.Blob)} bytes)");

            // MaxStack, read back out of the fat headers rather than trusted.
            int fat = 0;
            foreach (var mh in md.MethodDefinitions) {
                var m = md.GetMethodDefinition(mh);
                if (m.RelativeVirtualAddress == 0) continue;
                var bb = pe.GetMethodBody(m.RelativeVirtualAddress);
                string nm = md.GetString(m.Name);
                if (!bb.LocalSignature.IsNil) fat++;
                Want(bb.MaxStack > 0 && bb.MaxStack <= 8,
                     $"{nm} declares MaxStack {bb.MaxStack}");
            }
            Want(fat >= 1, $"at least one body needed the fat format ({fat})");

            // The FieldRva row, which is where the bytecode is.
            imageLen = 0;
            foreach (var th in md.TypeDefinitions) {
                var t = md.GetTypeDefinition(th);
                if (md.GetString(t.Name).StartsWith("$ArrayType$")) {
                    imageLen = t.GetLayout().Size;
                }
                foreach (var fh in t.GetFields()) {
                    var f = md.GetFieldDefinition(fh);
                    if (md.GetString(f.Name) == "IMAGE") {
                        Want(f.GetRelativeVirtualAddress() != 0,
                             $"IMAGE has a FieldRva (0x{f.GetRelativeVirtualAddress():x})");
                    }
                }
            }
            Want(imageLen > 0, $"the bytecode's length is declared in a ClassLayout ({imageLen} bytes)");
        }

        // --- the loader.
        var asm = Assembly.LoadFrom(path);
        Ok($"the CLR loads it: {asm.GetName().Name}");

        // --- METADATA WITHOUT EXECUTING ANYTHING. Not a call, not a boot; this
        // is what replaced the `prop` operation, and the whole point is that it
        // works before deciding whether to load the program at all.
        var meta = asm.GetCustomAttribute<_3sln.Flint.MetaAttribute>();
        Want(meta != null, "the metadata is a CustomAttribute, read without calling in");
        if (meta != null) {
            Want(meta.Edn.Contains(":version"), "the metadata carries :version");
            Want(meta.Edn.Contains(":abi :clr"), "the metadata says :abi :clr");
            Want(meta.Edn.Contains(":key"), "the metadata carries a compatibility key");
        }
        bool named = false;
        foreach (var d in CustomAttributeData.GetCustomAttributes(asm)) {
            if (d.AttributeType.FullName == "_3sln.Flint.MetaAttribute") named = true;
        }
        Want(named, "the attribute type is namespaced _3sln.Flint, not a bare name");

        // --- the witness methods. Compared against values computed HERE, because
        // a FieldRva array at a wrong offset answers a plausible wrong number.
        var ty = asm.GetType("Program");
        var image = (byte[]) ty.GetMethod("Image").Invoke(null, null);
        Want(image.Length == imageLen, $"Image() is {image.Length} bytes, matching the ClassLayout");
        Want(image.Length > 8 && image[0] == 'F' && image[1] == 'L' && image[2] == 'I',
             "the embedded bytes begin FLINTIMG");

        long sum = 0; foreach (var b in image) sum += b;
        Want((int) ty.GetMethod("Sum").Invoke(null, null) == sum,
             $"Sum() agrees with a sum computed outside the assembly ({sum})");

        unchecked {
            long h = -3750763034362895579L;                       // 0xcbf29ce484222325
            foreach (var b in image) h = (h ^ (b & 0xff)) * 1099511628211L;
            long got = (long) ty.GetMethod("Fnv1a").Invoke(null, null);
            Want(got == h, $"Fnv1a() -- emitted IL -- agrees with this C# ({h})");
            // And with the RUNTIME's own, which `Img.Load` computes. Three
            // independent implementations, one number.
            Want(true, "(the runtime's own fingerprint is checked after boot)");
        }
        Want((int) ty.GetMethod("LongBranch").Invoke(null, null) == 1,
             "LongBranch() returns 1, so the widened branch landed where it meant to");

        // --- the program, through the three operations.
        var bridge = new Bridge();
        ty.GetMethod("Boot").Invoke(null, new object[]{ bridge });
        Ok("Boot: the bridge became the system port, and every native resolved");

        long fp = Artifact.Runtime.fingerprint;
        unchecked {
            long h = -3750763034362895579L;
            foreach (var b in image) h = (h ^ (b & 0xff)) * 1099511628211L;
            Want(fp == h, $"the runtime's own fingerprint agrees too ({fp})");
        }

        bridge.Out.Enqueue(new Artifact.Msg(Artifact.SystemPort,
            new W().Map(2).Kw("op").Kw("bind").Kw("port").Port(CALL_PORT).Done()));
        bridge.Out.Enqueue(new Artifact.Msg(CALL_PORT,
            new W().Map(4).Kw("tx").Num(1).Kw("op").Kw("call")
                   .Kw("fn").Str(fn).Kw("args").Vec(1).Vec(0).Done()));

        var loop = ty.GetMethod("Loop");
        int status = (int) loop.Invoke(null, null);
        for (int i = 0; i < 16 && bridge.In.Count == 0 && status == 2; i++) {
            status = (int) loop.Invoke(null, null);
        }
        Ok($"Loop: {(Artifact.Status) status}");

        // The answer. Scanned out of the wire bytes rather than decoded, which is
        // all this needs -- the codec has its own tests.
        // AN EMPTY `expected` MEANS THERE IS NOTHING TO COMPARE AGAINST, and it
        // must not read as a pass. `bin/check-clr` passes "" when the native CLI
        // is not built, because the alternative it tried first -- falling back to
        // a constant -- printed a passing comparison against a number in the
        // script.
        if (expected.Length == 0) {
            Console.WriteLine("--   the answer was NOT compared: no interpreter to compare against");
            Want(bridge.In.Count > 0, $"the program at least answered something ({Render(bridge.In)})");
        } else {
            string found = null;
            foreach (var m in bridge.In) {
                foreach (var s in Strings(m.Bytes)) if (s == expected) found = s;
            }
            Want(found == expected,
                 $"the program answered {Quote(expected)}, the same as the interpreter" +
                 (found == expected ? "" : $" -- got {Render(bridge.In)}"));
        }

        Console.WriteLine(fails == 0
            ? $"clr artifact: every check passed ({imageLen} bytes of bytecode in .text)"
            : $"clr artifact: {fails} FAILED");
        return fails == 0 ? 0 : 1;
    }

    static string Quote(string s) => "\"" + s + "\"";

    static IEnumerable<string> Strings(byte[] b) {
        for (int i = 0; i + 4 < b.Length; i++) {
            if (b[i] != Codec.K_STRING) continue;
            long n = (b[i+1] & 0xffL) | ((b[i+2] & 0xffL) << 8)
                   | ((b[i+3] & 0xffL) << 16) | ((b[i+4] & 0xffL) << 24);
            if (n <= 0 || n > 256 || i + 5 + n > b.Length) continue;
            var s = Encoding.UTF8.GetString(b, i + 5, (int) n);
            bool printable = true;
            foreach (var c in s) if (c < 0x20 || c > 0x7e) printable = false;
            if (printable) yield return s;
        }
    }

    static string Render(List<Artifact.Msg> msgs) {
        var sb = new StringBuilder();
        foreach (var m in msgs) foreach (var s in Strings(m.Bytes)) {
            sb.Append(sb.Length > 0 ? ", " : "").Append('"').Append(s).Append('"');
        }
        return sb.Length > 0 ? sb.ToString() : "(nothing)";
    }
}
