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


    // --- report mode -------------------------------------------------------
    //
    // ONE JUDGE, N REPORTERS. This mode OBSERVES and does not decide: it prints
    // `key<TAB>arg<TAB>value` and `bin/check-four-ops` compares every target's
    // rows against the one contract. The self-judging mode below stays, because
    // `bin/check-clr` wants a verdict about THIS target's container; but "the
    // three operations mean the same thing everywhere" is not a question a single
    // target can answer about itself, and letting each one try is how "same
    // semantics" decays into "each target's own opinion".

    static void Row(string key, string arg, string value) {
        // `(absent)` for a missing answer, and escapes so a value can never
        // contain the field separator or a line break. A reporter whose output can
        // be reshaped by the thing it is reporting is not a reporter.
        string v = value == null ? "(absent)"
                 : value.Replace("\\", "\\\\").Replace("\t", "\\t")
                        .Replace("\r", "\\r").Replace("\n", "\\n");
        Console.WriteLine(key + "\t" + arg + "\t" + v);
    }

    /// A native this image actually imports, so `boot missing` tests a real gap.
    ///
    /// NOT A HARD-CODED NAME. `flint/fabs` was hard-coded here first and the
    /// refusal did not name it -- because the program does not import it. An image
    /// imports what its NAMESPACES mention, so the only safe choice is one the
    /// image itself declares.
    static string SomeImportedNative(byte[] image) {
        var probe = new Rt(1L << 20, 1L << 26);
        var l = Img.Load(probe, image);
        return l != null && l.nativeNames.Length > 0 ? l.nativeNames[0] : null;
    }

    static int Report(string path, string fn, string[] metaNames) {
        var asm = Assembly.LoadFrom(path);
        var ty = asm.GetType("Program");

        // --- ops. THE CANONICAL NAMES, lowercased, not this target's spelling.
        // The CLR spells them `Boot`/`Loop`/`Link` and the JVM spells them
        // `boot`/`loop`/`link`; a judge comparing raw strings across targets would
        // report a divergence the contract explicitly permits. So the reporter
        // normalises and the contract carries the per-target spelling.
        //
        // `loop` is looked for on the SANDBOX, not on the artifact's own type.
        // That is the shape, not an accident: `boot` answers a sandbox and `loop`
        // is its, because a static `Loop` means one sandbox per load context.
        var found = new List<string>();
        if (ty.GetMethod("Boot") != null) found.Add("boot");
        if (ty.GetMethod("Link") != null) found.Add("link");
        if (typeof(Artifact).GetMethod("Loop") != null) found.Add("loop");
        found.Sort();
        Row("ops", "names", string.Join(",", found));

        // --- meta. The carrier answers; a bare name must not.
        var meta = asm.GetCustomAttribute<_3sln.Flint.MetaAttribute>();
        foreach (var name in metaNames) {
            bool carrier = name == "_3sln.Flint.MetaAttribute"
                        || name == "com.3sln.flint.meta";
            Row("meta", name, carrier ? meta?.Edn : null);
        }

        var image = (byte[]) ty.GetMethod("Image").Invoke(null, null);
        var bridge = new Bridge();
        var link = ty.GetMethod("Link");
        var boot = ty.GetMethod("Boot");

        // --- link, before boot.
        try {
            link.Invoke(null, new object[]{ bridge, "flint/__probe",
                                            (Func<byte[],byte[]>) (w => w) });
            Row("link", "before", "accepted");
        } catch (Exception e) {
            Row("link", "before", "refused: " + (e.InnerException ?? e).Message);
        }

        // --- boot, with a native missing. MUST fail.
        string victim = SomeImportedNative(image);
        if (victim == null) {
            Row("boot", "missing", "(no natives imported, nothing to remove)");
            Row("link", "restore", null);
        } else {
            Artifact.LinkRaw(bridge, victim, null);
            try {
                boot.Invoke(null, new object[]{ bridge });
                Row("boot", "missing", "BOOTED ANYWAY");
            } catch (TargetInvocationException e) {
                Row("boot", "missing", e.InnerException.Message);
            }
            // NOTHING TO RESTORE, and that is a property of the instance design
            // rather than luck: hooks are keyed per bridge and `Boot` CONSUMES
            // them, so the failed attempt took the poisoned hook with it. The
            // static version had to put a global table entry back by hand, and a
            // silent failure there would have made every row below a lie.
            Row("link", "restore", null);
        }

        // --- boot, for real. Same bridge: the failed attempt never registered it.
        object sandbox = null;
        try {
            sandbox = boot.Invoke(null, new object[]{ bridge });
            Row("boot", "ok", "booted");
        } catch (TargetInvocationException e) {
            Row("boot", "ok", e.InnerException.Message);
        }

        // --- link, after boot. MUST refuse.
        try {
            link.Invoke(null, new object[]{ bridge, "flint/__late",
                                            (Func<byte[],byte[]>) (w => w) });
            Row("link", "after", "accepted");
        } catch (Exception) { Row("link", "after", "refused"); }

        if (sandbox == null) {
            Row("loop", "at-rest", null);
            Row("loop", "after-call", null);
            Row("answer", "printable", null);
            return 0;
        }

        // --- loop, at rest and after a call. On the SANDBOX.
        var a = (Artifact) sandbox;
        Row("loop", "at-rest", ((int) a.Loop()).ToString());

        bridge.Out.Enqueue(new Artifact.Msg(Artifact.SystemPort,
            new W().Map(2).Kw("op").Kw("bind").Kw("port").Port(CALL_PORT).Done()));
        bridge.Out.Enqueue(new Artifact.Msg(CALL_PORT,
            new W().Map(4).Kw("tx").Num(1).Kw("op").Kw("call")
                   .Kw("fn").Str(fn).Kw("args").Vec(1).Vec(0).Done()));
        int status = (int) a.Loop();
        for (int i = 0; i < 16 && bridge.In.Count == 0 && status == 2; i++) {
            status = (int) a.Loop();
        }
        Row("loop", "after-call", status.ToString());

        string answer = null;
        foreach (var m in bridge.In) foreach (var s in Strings(m.Bytes)) answer = s;
        Row("answer", "printable", answer);
        return 0;
    }

    public static int Main(string[] argv) {
        if (argv.Length >= 1 && argv[0] == "--report") {
            if (argv.Length < 3) {
                Console.Error.WriteLine("usage: Check --report <assembly> <ns/fn> [meta-name ...]");
                return 2;
            }
            var names = argv.Length > 3
                ? argv[3..]
                : new[]{ "_3sln.Flint.MetaAttribute", "com.3sln.flint.meta", "flint" };
            return Report(Path.GetFullPath(argv[1]), argv[2], names);
        }
        if (argv.Length < 2) {
            Console.Error.WriteLine("usage: Check <assembly> <ns/fn> [expected]");
            Console.Error.WriteLine("       Check --report <assembly> <ns/fn> [meta-name ...]");
            return 2;
        }
        string path = Path.GetFullPath(argv[0]), fn = argv[1];
        string expected = argv.Length > 2 ? argv[2] : "";

        // --- the metadata tables, read straight off disk. Before anything is
        // loaded, because a malformed table should be named as such rather than as
        // "the assembly would not load".
        int imageLen;
        using (var fs = File.OpenRead(path)) {
            using var pe = new PEReader(fs);
            var md = pe.GetMetadataReader();
            Want(pe.PEHeaders.SectionHeaders.Length == 1,
                 $"one section, no .reloc and no import table ({pe.PEHeaders.SectionHeaders.Length})");
            Want((int) pe.PEHeaders.CorHeader.Flags == 1, "CorFlags is ILONLY");
            Want(md.GetHeapSize(HeapIndex.Blob) < 0x10000,
                 $"the blob heap stays under 64 KB, so every index stays 2 bytes ({md.GetHeapSize(HeapIndex.Blob)} bytes)");

            int fat = 0;
            foreach (var mh in md.MethodDefinitions) {
                var m = md.GetMethodDefinition(mh);
                if (m.RelativeVirtualAddress == 0) continue;
                var bb = pe.GetMethodBody(m.RelativeVirtualAddress);
                string nm = md.GetString(m.Name);
                if (!bb.LocalSignature.IsNil) fat++;
                // MaxStack, read back out of the header rather than trusted.
                Want(bb.MaxStack > 0 && bb.MaxStack <= 8, $"{nm} declares MaxStack {bb.MaxStack}");
            }
            Want(fat >= 1, $"at least one body needed the fat format ({fat})");

            imageLen = 0;
            foreach (var th in md.TypeDefinitions) {
                var t = md.GetTypeDefinition(th);
                if (md.GetString(t.Name).StartsWith("$ArrayType$")) imageLen = t.GetLayout().Size;
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

        var asm = Assembly.LoadFrom(path);
        Ok($"the CLR loads it: {asm.GetName().Name}");

        // --- METADATA WITHOUT EXECUTING ANYTHING. Not a call, not a boot; this is
        // what replaced the `prop` operation, and the whole point is that it works
        // before deciding whether to load the program at all.
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

        long want;
        unchecked {
            long h = -3750763034362895579L;                       // 0xcbf29ce484222325
            foreach (var b in image) h = (h ^ (b & 0xff)) * 1099511628211L;
            want = h;
        }
        Want((long) ty.GetMethod("Fnv1a").Invoke(null, null) == want,
             $"Fnv1a() -- emitted IL -- agrees with this C# ({want})");
        Want((int) ty.GetMethod("LongBranch").Invoke(null, null) == 1,
             "LongBranch() returns 1, so the widened branch landed where it meant to");

        // --- the program, through the three operations. `Boot` answers the
        // sandbox; `Loop` is the sandbox's.
        var bridge = new Bridge();
        var a = (Artifact) ty.GetMethod("Boot").Invoke(null, new object[]{ bridge });
        Ok("Boot: the bridge became the system port, and every native resolved");
        Want(a != null, "Boot answered a sandbox rather than nothing");

        Want(a.Runtime.fingerprint == want,
             $"the runtime's own fingerprint agrees too ({a.Runtime.fingerprint})");

        bridge.Out.Enqueue(new Artifact.Msg(Artifact.SystemPort,
            new W().Map(2).Kw("op").Kw("bind").Kw("port").Port(CALL_PORT).Done()));
        bridge.Out.Enqueue(new Artifact.Msg(CALL_PORT,
            new W().Map(4).Kw("tx").Num(1).Kw("op").Kw("call")
                   .Kw("fn").Str(fn).Kw("args").Vec(1).Vec(0).Done()));

        int status = (int) a.Loop();
        for (int i = 0; i < 16 && bridge.In.Count == 0 && status == 2; i++) status = (int) a.Loop();
        Ok($"Loop: {(Artifact.Status) status}");

        // A SECOND SANDBOX, which the static version could not have. Its own
        // bridge, its own image, its own scheduler -- and the first one still
        // answers afterwards, which is the property the instance rewrite bought.
        var bridge2 = new Bridge();
        var b2 = (Artifact) ty.GetMethod("Boot").Invoke(null, new object[]{ bridge2 });
        Want(b2 != null && !ReferenceEquals(a, b2),
             "a second sandbox boots in the same load context, and is not the first");
        Want(b2.Runtime != a.Runtime, "the two sandboxes hold different runtimes");

        if (expected.Length == 0) {
            Console.WriteLine("--   the answer was NOT compared: no interpreter to compare against");
            Want(bridge.In.Count > 0, $"the program at least answered something ({Render(bridge.In)})");
        } else {
            string found = null;
            foreach (var m in bridge.In) foreach (var s in Strings(m.Bytes)) if (s == expected) found = s;
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
