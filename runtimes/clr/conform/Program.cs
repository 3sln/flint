using Flint;
using _3sln.Flint.Kgen.Rt;

/// Run an image and print what it returned.
///
/// The same shape as the JVM's runner: one source, compiled once, run on every
/// runtime, answers diffed. `doc/decisions/0010` is explicit that the bytecode
/// makes a port cheap and does nothing to make two ports AGREE.
public static class Program {
    public static int Main(string[] args) {
        if (args.Length >= 1 && args[0] == "--rt-foundation") return RtFoundation();
        if (args.Length >= 1 && args[0] == "--rt-snapshot") return RtSnapshot();
        if (args.Length >= 1 && args[0] == "--rt-hash") return RtHash();
        if (args.Length >= 1 && args[0] == "--rt-maps") return RtMaps();
        if (args.Length >= 1 && args[0] == "--rt-parallel") return RtParallel();
        if (args.Length >= 1 && args[0] == "--rt-stale") return RtStale();
        if (args.Length >= 3 && args[0] == "--rt-shelve") return RtShelve(args[1], args[2]);
        if (args.Length >= 3 && args[0] == "--rt-selfhost") return RtSelfHost(args[1], args[2]);
        if (args.Length >= 2 && args[0] == "--rt-aot") return RtAot(args[1]);
        if (args.Length >= 2 && args[0] == "--rt-flags") return RtFlags(args[1]);
        if (args.Length >= 2 && args[0] == "--rt-hostports") return RtHostPorts(args[1]);
        if (args.Length >= 2 && args[0] == "--rt-gas") return RtGas(args[1]);
        if (args.Length >= 2 && args[0] == "--rt-steps") return RtSteps(args[1]);
        if (args.Length >= 2 && args[0] == "--rt-image")
            return RtImage(args[1], args.Length > 2 ? args[2] : null);
        // No bare-argument form any more. It ran an image on the BOXED port,
        // which is gone; `--rt-image` is the same thing on the runtime that
        // replaced it, and it also compares against the native answer rather
        // than merely printing one.
        Console.WriteLine("usage: Conform --rt-<mode> [args]");
        return 2;
    }

    /// The ported runtime's foundation: values, memory and object layout.
    ///
    /// The SAME assertions as `runtimes/jvm/test/RtFoundation.java`, in the same
    /// order, because the two are meant to be verbatim mirrors and the only way
    /// to know that is to check rather than to intend it.
    ///
    /// The number at the end is the cost of the REPRESENTATION, with no
    /// dispatch -- not an end-to-end speedup and not to be quoted as one. What
    /// it settles is that boxing is no longer the ceiling.

    // ------------------------------------------------------------------
    // Snapshots on the ported runtime: both formats, and the properties that
    // distinguish them.
    //
    // A LINE-FOR-LINE MIRROR of the JVM's `RtSnapshot.java`, down to the
    // wording of every line it prints, because the gate `cmp`s the two
    // outputs. Anything that differed between the ports would have to differ
    // in a printed number for that check to catch it -- so the numbers printed
    // are the ones that would move: byte counts, and whether an address
    // changed.

    static int snapFails;

    private static void SnapOk(string what, bool cond) {
        Console.WriteLine((cond ? "  ok   " : "  FAIL ") + what);
        if (!cond) snapFails++;
    }

    /// A structure with enough shape to be wrong in a detectable way: a vector
    /// of vectors, so the export has to get INTERIOR references right, plus a
    /// string so a non-`Vals` layout is exercised.
    private static long SnapBuild(Flint.Rt.Rt rt, int n) {
        int bas = rt.Mark();
        int vec = rt.Push(Flint.Rt.Vec.Empty(rt));
        for (int i = 0; i < n; i++) {
            int inner = rt.Push(Flint.Rt.Vec.Empty(rt));
            rt.SetR(inner, Flint.Rt.Vec.Conj(rt, rt.R(inner), Flint.Rt.Val.Fixnum(i)));
            rt.SetR(inner, Flint.Rt.Vec.Conj(rt, rt.R(inner), Flint.Rt.Str.Of(rt, "item-" + i)));
            rt.SetR(vec, Flint.Rt.Vec.Conj(rt, rt.R(vec), rt.R(inner)));
            rt.PopTo(inner);
        }
        long outv = rt.R(vec);
        rt.PopTo(bas);
        return outv;
    }

    /// Read the structure back as a string, so one comparison covers every
    /// element, its type, and its order.
    private static string SnapRender(Flint.Rt.Rt rt, long v) {
        var sb = new System.Text.StringBuilder();
        int n = Flint.Rt.Vec.Count(rt, v);
        for (int i = 0; i < n; i++) {
            long inner = Flint.Rt.Vec.Nth(rt, v, i, Flint.Rt.Val.NotFound);
            sb.Append(Flint.Rt.Val.AsFixnum(Flint.Rt.Vec.Nth(rt, inner, 0, Flint.Rt.Val.NotFound)));
            sb.Append('=');
            sb.Append(Flint.Rt.Str.Text(rt, Flint.Rt.Vec.Nth(rt, inner, 1, Flint.Rt.Val.NotFound)));
            sb.Append(';');
        }
        return sb.ToString();
    }

    private static int RtSnapshot() {
        const int N = 300;

        // --- the verbatim format: a memcpy, restored at identical addresses.
        var a = new Flint.Rt.Rt(1024 * 1024, 64L * 1024 * 1024);
        a.fingerprint = 0xABCDEF12345L;
        long root = SnapBuild(a, N);
        a.roots.shared.Globals = new long[]{ root };
        string before = SnapRender(a, root);
        SnapOk("built a structure to snapshot: " + N + " entries", before.StartsWith("0=item-0;"));

        byte[] verbatim = Flint.Rt.Snap.Capture(a);
        SnapOk("capture produced bytes: " + verbatim.Length, verbatim.Length > 1024);

        var b = new Flint.Rt.Rt(1024 * 1024, 64L * 1024 * 1024);
        b.fingerprint = a.fingerprint;
        b.roots.shared.Globals = new long[1];
        SnapOk("restore accepts it", Flint.Rt.Snap.Restore(b, verbatim));
        SnapOk("the restored heap reads back identically", SnapRender(b, b.roots.shared.Globals[0]) == before);
        SnapOk("and at the SAME address, which is what a memcpy means",
               Flint.Rt.Val.AsHeap(b.roots.shared.Globals[0]) == Flint.Rt.Val.AsHeap(root));

        // A snapshot restored against a DIFFERENT program does not fail -- it
        // quietly means something else. The fingerprint is what makes that
        // refusable, so check that it actually refuses.
        var wrong = new Flint.Rt.Rt(1024 * 1024, 64L * 1024 * 1024);
        wrong.fingerprint = 0x999L;
        SnapOk("refuses a snapshot from another image", !Flint.Rt.Snap.Restore(wrong, verbatim));
        SnapOk("and says WHICH check failed", Flint.Rt.Snap.Refused == Flint.Rt.Snap.RefuseImage);
        byte[] corrupt = (byte[]) verbatim.Clone();
        corrupt[4] = 0xFF;                       // the version word
        SnapOk("refuses a layout it does not speak", !Flint.Rt.Snap.Restore(b, corrupt));
        SnapOk("and distinguishes that from a wrong image",
               Flint.Rt.Snap.Refused == Flint.Rt.Snap.RefuseLayout);

        // --- the live format: a traversal, and it must RELOCATE.
        var c = new Flint.Rt.Rt(1024 * 1024, 64L * 1024 * 1024);
        c.fingerprint = 0xABCDEF12345L;
        long croot = SnapBuild(c, N);
        c.roots.shared.Globals = new long[]{ croot };
        byte[] live = Flint.Rt.Snap.ExportLive(c);
        SnapOk("exportLive agrees with the collector about what is live", live != null);
        SnapOk("and is smaller than the memcpy, being the data rather than the heap: "
               + live.Length + " < " + verbatim.Length, live.Length < verbatim.Length);

        // A DIFFERENT nursery, so nothing can come back where it started by luck.
        var d = new Flint.Rt.Rt(3 * 1024 * 1024, 64L * 1024 * 1024);
        d.fingerprint = c.fingerprint;
        // Sized as an image load would size it. The import fills var slots, it
        // does not create them: the slots belong to the program, and a snapshot
        // that could add them would be carrying code after all.
        d.roots.shared.Globals = new long[1];
        SnapOk("importLive accepts it", Flint.Rt.Snap.ImportLive(d, live));
        SnapOk("the rehydrated heap reads back identically", SnapRender(d, d.roots.shared.Globals[0]) == before);
        SnapOk("at a DIFFERENT address, which is what relocating means",
               Flint.Rt.Val.AsHeap(d.roots.shared.Globals[0]) != Flint.Rt.Val.AsHeap(croot));

        // The rehydrated heap has to be a working heap, not just a readable
        // one: keep allocating on it and collect, which is what would trip a
        // bad remembered set or a missed write barrier from pass two.
        long more = SnapBuild(d, 200);
        d.roots.shared.Globals = new long[]{ d.roots.shared.Globals[0], more };
        d.gc.Major(d.roots);
        SnapOk("survives a major collection after import",
               SnapRender(d, d.roots.shared.Globals[0]) == before);
        SnapOk("and the objects allocated after it are intact too",
               SnapRender(d, d.roots.shared.Globals[1]).StartsWith("0=item-0;"));

        // --- shelving: the halt that leaves nothing runnable.
        Flint.Rt.Snap.Halt(d);
        SnapOk("halt leaves nothing to run", d.frames.Count == 0 && d.roots.StackTop == 0);
        SnapOk("and says the sandbox was shelved rather than answered",
               d.status == Flint.Rt.Snap.StatusShelved);

        if (snapFails > 0) { Console.WriteLine("  " + snapFails + " failed"); return 1; }
        return 0;
    }


    /// The ported runtime, on a REAL compiled image. A mirror of the JVM's
    /// `RtImage.java`, printing the same lines so the gate can compare them.
    ///
    /// It reports HOW FAR it gets rather than passing or failing outright,
    /// because the standard library is not ported yet and "loaded 85 functions
    /// and then wanted `transient`" is the useful answer while that is true. A
    /// silent pass here would be the misleading one.
    /// What was thrown, said in flint's terms rather than the host's.
    static string Why(Flint.Rt.Rt rt) {
        long t = rt.thrown;
        if (rt.IsException(t) && Flint.Rt.Str.IsString(rt, rt.ExMessage(t)))
            return "the program threw " + Flint.Rt.Str.Text(rt, rt.ExKind(t))
                 + ": " + Flint.Rt.Str.Text(rt, rt.ExMessage(t));
        return "the program threw " + rt.Describe(t);
    }

    // ------------------------------------------------------------------
    // AOT: the same program interpreted and compiled, and the answers diffed.
    //
    // A MIRROR of `runtimes/jvm/test/RtAot.java`. The only thing worth
    // asserting about a backend is that IT DID NOT CHANGE THE ANSWER -- fast
    // and wrong is worse than absent, and "it ran" does not tell them apart.

    private readonly record struct AotRun(string Out, long Steps, int Compiled, long Entries);

    private static AotRun AotGo(string path, bool aot, bool chunkAll) {
        var rt = new Flint.Rt.Rt(4L * 1024 * 1024, 512L * 1024 * 1024);
        var img = Flint.Rt.Img.Load(rt, File.ReadAllBytes(path));
        if (img == null) return new AotRun("FAIL not a flint image", 0, 0, 0);
        int n = aot ? rt.CompileArities(chunkAll) : 0;
        Flint.Rt.Rt.aotEntries = 0;
        foreach (int fn in img.init) {
            rt.Call(rt.MakeClosure(fn, System.Array.Empty<long>()), System.Array.Empty<long>());
            if (!Flint.Rt.Val.IsNil(rt.thrown))
                return new AotRun(AotWhy(rt), rt.steps, n, Flint.Rt.Rt.aotEntries);
        }
        long v = rt.RunProgram(rt.MakeClosure(img.entry, System.Array.Empty<long>()),
                               new long[]{ Flint.Rt.Val.Nil });
        if (!Flint.Rt.Val.IsNil(rt.thrown))
            return new AotRun(AotWhy(rt), rt.steps, n, Flint.Rt.Rt.aotEntries);
        return new AotRun(
            Flint.Rt.Str.IsString(rt, v) ? Flint.Rt.Str.Text(rt, v) : rt.Describe(v),
            rt.steps, n, Flint.Rt.Rt.aotEntries);
    }

    private static string AotWhy(Flint.Rt.Rt rt) {
        long t = rt.thrown;
        if (rt.IsException(t) && Flint.Rt.Str.IsString(rt, rt.ExMessage(t)))
            return "threw " + Flint.Rt.Str.Text(rt, rt.ExKind(t)) + ": "
                 + Flint.Rt.Str.Text(rt, rt.ExMessage(t));
        return "threw " + rt.Describe(t);
    }

    private static int RtAot(string path) {
        var interp = AotGo(path, false, false);
        var comp = AotGo(path, true, false);
        if (comp.Compiled == 0) {
            Console.WriteLine("  FAIL nothing compiled, so this asserts nothing");
            return 1;
        }
        // AND ENTERED. Compiling is not running: the JVM port's first version
        // left the frame's re-entry point at NEVER, so every arity compiled and
        // not one instruction of compiled code ever executed -- and every
        // assertion below passed, because an interpreter agrees with itself.
        if (comp.Entries == 0) {
            Console.WriteLine("  FAIL " + comp.Compiled
                              + " arities compiled and compiled code was never ENTERED");
            return 1;
        }
        if (interp.Out != comp.Out) {
            Console.WriteLine("  FAIL compiled and interpreted disagree");
            Console.WriteLine("        interpreted " + interp.Out);
            Console.WriteLine("        compiled    " + comp.Out);
            return 1;
        }
        // MAXIMAL CHUNKING as well. If a failure survives it then no boundary
        // was missing and the fault is in how an opcode is emitted; if it does
        // not, a boundary is. Those are the two halves this can be wrong in and
        // they want opposite fixes.
        var all = AotGo(path, true, true);
        if (interp.Out != all.Out) {
            Console.WriteLine("  FAIL compiled disagrees under maximal chunking");
            Console.WriteLine("        interpreted " + interp.Out);
            Console.WriteLine("        compiled    " + all.Out);
            return 1;
        }
        if (interp.Steps != comp.Steps) {
            Console.WriteLine("  FAIL the gas counts differ, so the chunking is wrong");
            Console.WriteLine("        interpreted " + interp.Steps);
            Console.WriteLine("        compiled    " + comp.Steps);
            return 1;
        }
        Console.WriteLine("  ok   " + comp.Compiled + " arities compiled, entered "
                          + comp.Entries + " times, same answer and same gas ("
                          + interp.Steps + ")");
        return 0;
    }

    /// What the compiler decided, and what this runtime does about it.
    ///
    /// A LINE-FOR-LINE MIRROR of `runtimes/jvm/test/RtFlags.java`, including
    /// the `aot=false`: the boxed port compiled arities to host bytecode at
    /// load time and could, because every value was already a host object.
    /// This runtime's values are NaN-boxed longs in a flat heap, so the same
    /// feature is a new backend rather than a port. `doc/ports.md` records it.
    ///
    /// What survives is the assertion worth keeping: the decision REACHES the
    /// runtime. A flag the compiler writes and no runtime reads is one that can
    /// silently stop being written.
    private static int RtFlags(string path) {
        var rt = new Flint.Rt.Rt(1024 * 1024, 16L * 1024 * 1024);
        var img = Flint.Rt.Img.Load(rt, File.ReadAllBytes(path));
        if (img == null) { Console.WriteLine("FAIL not a flint image"); return 1; }
        Console.WriteLine("flags=" + img.flags + " aot=false");
        return 0;
    }

    // ------------------------------------------------------------------
    // THE HOST, driving a real image through the port protocol.
    //
    // `runtimes/conform/hostport.cljc` is the program; this is the other half
    // of it. The native driver (`units-src/flint-conc/src/bin/hostports.rs`)
    // and the JVM's (`RtHostPorts.java`) run the SAME image through the SAME
    // script, and `bin/conform-hosts` compares the three transcripts.
    //
    // That comparison is the whole point. The host protocol is an ABI: a token
    // whose generation must be checked, a byte budget that must be respected in
    // both directions, an event layout with five fields per record. Three
    // runtimes implementing an ABI separately are three ABIs until something
    // makes them say the same thing out loud.

    private readonly record struct Ev(int Kind, int A, int B, byte[] Payload);

    private static string KindName(int k) => k switch {
        Flint.Rt.Conc.EV_OPEN => "open",
        Flint.Rt.Conc.EV_MESSAGE => "message",
        Flint.Rt.Conc.EV_CLOSED => "closed",
        Flint.Rt.Conc.EV_RETAIN => "retain",
        Flint.Rt.Conc.EV_RELEASE => "release",
        _ => "?",
    };

    private static string StateName(long s) => (int) s switch {
        Flint.Rt.Conc.P_PENDING => "pending",
        Flint.Rt.Conc.P_OPEN => "open",
        Flint.Rt.Conc.P_CLOSED => "closed",
        Flint.Rt.Conc.P_HALF => "half-closed",
        Flint.Rt.Conc.P_ORPHANED => "orphaned",
        Flint.Rt.Conc.P_REFUSED => "refused",
        255 => "unknown",
        _ => "?",
    };

    private static int Le32(byte[] b, int at) =>
        b[at] | (b[at + 1] << 8) | (b[at + 2] << 16) | (b[at + 3] << 24);

    private static Ev[] Drain(Flint.Rt.Rt rt) {
        var ev = Flint.Rt.Conc.DrainEvents(rt);
        byte[] buf = ev.Bytes;
        var outv = new Ev[ev.Count];
        for (int i = 0; i < ev.Count; i++) {
            int r = i * 20;
            int off = Le32(buf, r + 12), len = Le32(buf, r + 16);
            var p = new byte[len];
            System.Array.Copy(buf, off, p, 0, len);
            outv[i] = new Ev(Le32(buf, r), Le32(buf, r + 4), Le32(buf, r + 8), p);
        }
        return outv;
    }

    private static string Show(System.Collections.Generic.List<Ev> evs) {
        var b = new System.Text.StringBuilder();
        foreach (var e in evs) {
            if (b.Length > 0) b.Append(' ');
            // EVERY payload is the wire format now: a bridge carries values and
            // the runtime encodes them, so a message reads the same way an
            // open-request does rather than as opaque bytes.
            string p = e.Payload.Length == 0 ? "" : Render(e.Payload);
            b.Append(KindName(e.Kind)).Append('(').Append(e.A).Append(',').Append(e.B)
             .Append(',').Append(p).Append(')');
        }
        return b.ToString();
    }

    /// The open-request payload, rendered.
    ///
    /// It is an ENCODED VALUE rather than a bare name, which is what "the host
    /// does what it wants with the args" means in practice: this driver decodes
    /// it the way any host would, and an opaque value arrives carrying the id
    /// the host issued -- the whole of the capability check, done here rather
    /// than by the runtime.
    private static int ri;
    private static string Render(byte[] b) { ri = 0; return One(b); }

    private static int U32r(byte[] b) {
        int v = b[ri] | (b[ri+1] << 8) | (b[ri+2] << 16) | (b[ri+3] << 24);
        ri += 4;
        return v;
    }

    private static string Str_(byte[] b) {
        int n = U32r(b);
        string s = System.Text.Encoding.UTF8.GetString(b, ri, n);
        ri += n;
        return s;
    }

    private static string One(byte[] b) {
        int t = b[ri++];
        switch (t) {
            case 0: return "nil";
            case 1: return "true";
            case 2: return "false";
            case 3: {
                long lo = (uint) U32r(b), hi = (uint) U32r(b);
                return ((hi << 32) | lo).ToString();
            }
            case 5: return "\"" + Str_(b) + "\"";
            case 6: case 7: {
                int save = ri;
                int ns = U32r(b);
                string nss = "";
                if (ns != -1) { ri = save; nss = Str_(b) + "/"; }
                return (t == 6 ? ":" : "") + nss + Str_(b);
            }
            case 8: case 9: case 11: {
                int n = U32r(b);
                var sb = new System.Text.StringBuilder("[");
                for (int i = 0; i < n; i++) { if (i > 0) sb.Append(' '); sb.Append(One(b)); }
                return sb.Append(']').ToString();
            }
            case 10: {
                int n = U32r(b);
                var sb = new System.Text.StringBuilder("{");
                for (int i = 0; i < n; i++) {
                    if (i > 0) sb.Append(", ");
                    sb.Append(One(b)).Append(' ').Append(One(b));
                }
                return sb.Append('}').ToString();
            }
            // The one that matters: an opaque value with the id the HOST gave
            // it. A guest-minted one has id 0 and is recognisably not ours.
            case 16: {
                long lo = (uint) U32r(b), hi = (uint) U32r(b);
                long id = (hi << 32) | lo;
                return "#opaque[\"" + Str_(b) + "\" id=" + id + "]";
            }
            default: return "#tag" + t;
        }
    }

    /// `main`, then the scheduler -- what a host's `run` does.
    private static long RunAll(Flint.Rt.Rt rt, Flint.Rt.Img.Loaded img) {
        foreach (int fn in img.init) {
            rt.Call(rt.MakeClosure(fn, System.Array.Empty<long>()), System.Array.Empty<long>());
            if (!Flint.Rt.Val.IsNil(rt.thrown) && !rt.Parked()) return Flint.Rt.Val.Nil;
        }
        // One opaque value PROJECTED IN as the entry's second argument, under an
        // id this driver chose. That is the whole of lending a capability: no
        // grant table, no declaration, and nothing in the runtime that knows
        // what it is for.
        int bas = rt.Mark();
        int ai = rt.Push(Flint.Rt.Vec.Empty(rt));
        int mi = rt.Push(Flint.Rt.Maps.Empty(rt));
        int ki = rt.Push(Flint.Rt.Str.Keyword(rt, null, "fs"));
        int li = rt.Push(Flint.Rt.Str.Of(rt, "fs"));
        int oi = rt.Push(rt.NewOpaque(rt.R(li), 7));
        rt.SetR(mi, _3sln.Flint.Kgen.Rt.Mapwrite.MapAssoc(rt, rt.R(mi), rt.R(ki), rt.R(oi)));
        int vi = rt.Push(Flint.Rt.Vec.Empty(rt));
        rt.SetR(vi, Flint.Rt.Vec.Conj(rt, rt.R(vi), rt.R(ai)));
        rt.SetR(vi, Flint.Rt.Vec.Conj(rt, rt.R(vi), rt.R(mi)));
        long pair = rt.R(vi);
        rt.PopTo(bas);
        return rt.RunProgram(rt.MakeClosure(img.entry, System.Array.Empty<long>()),
                             new long[]{ pair });
    }

    /// The status a host sees, in the same three values the Rust reports:
    /// 0 finished, 1 threw, 2 parked on the host.
    private static int Status(Flint.Rt.Rt rt) {
        if (rt.status != 0) return rt.status;
        return Flint.Rt.Val.IsNil(rt.thrown) ? 0 : 1;
    }

    private static string Rendered(Flint.Rt.Rt rt, long v) {
        if (!Flint.Rt.Val.IsNil(rt.thrown)) {
            long e = rt.thrown;
            return Flint.Rt.Str.Text(rt, rt.ExKind(e)) + ": " + Flint.Rt.Str.Text(rt, rt.ExMessage(e));
        }
        return Flint.Rt.Str.IsString(rt, v) ? Flint.Rt.Str.Text(rt, v) : rt.Describe(v);
    }

    /// The host's id for this sandbox's system port, and for the port it grants.
    /// The HOST picks both: a sandbox no longer mints port ids, which is the
    /// whole of `doc/decisions/0027`.
    private const int SYSTEM = 1, GRANTED = 500;

    /// One string, as the host writes it: the wire format, which is what the
    /// runtime decodes on the way in.
    private static byte[] WireStr(string s) {
        byte[] u = System.Text.Encoding.UTF8.GetBytes(s);
        byte[] outb = new byte[5 + u.Length];
        outb[0] = (byte) Flint.Rt.Codec.K_STRING;
        outb[1] = (byte) u.Length;
        outb[2] = (byte) (u.Length >> 8);
        outb[3] = (byte) (u.Length >> 16);
        outb[4] = (byte) (u.Length >> 24);
        System.Array.Copy(u, 0, outb, 5, u.Length);
        return outb;
    }

    private static int RtHostPorts(string path) {
        var rt = new Flint.Rt.Rt(4L * 1024 * 1024, 512L * 1024 * 1024);
        var img = Flint.Rt.Img.Load(rt, File.ReadAllBytes(path));
        if (img == null) { Console.WriteLine("  FAIL not a flint image"); return 1; }

        // A SYSTEM PORT, installed before anything runs: `open` is a request ON
        // one (`doc/decisions/0027`), and a sandbox given none can ask for
        // nothing.
        Flint.Rt.Conc.InstallSystemPort(rt, SYSTEM, Flint.Rt.Str.Of(rt, "system"));

        // 1. The program runs until it asks for something only the host has.
        long v = RunAll(rt, img);
        Console.WriteLine("  ok   the program parked on the host: status " + Status(rt));
        var evs = new System.Collections.Generic.List<Ev>(Drain(rt));
        Console.WriteLine("  ok   it asked: " + Show(evs));
        Ev open = evs.Find(e => e.Kind == Flint.Rt.Conc.EV_OPEN);
        int token = open.A;
        // The request came out ON THE SYSTEM PORT. There is no port for it yet
        // -- the answer is what creates one, and the host picks its id.
        int port = GRANTED;

        // 2. WHAT WAS FORWARDED, decoded. Nothing in the runtime looked at it
        //    on the way past, and nothing in it knows what a capability is.
        Console.WriteLine("  ok   it forwarded: " + Render(open.Payload));

        // 3. Grant it. A second answer on the same token is refused: the
        //    generation in it has moved on, so a late or duplicated reply cannot
        //    resume a stranger's thread.
        Console.WriteLine("  ok   a grant must name a port: "
                          + Low(Flint.Rt.Conc.HostContinue(rt, token, true)));
        Console.WriteLine("  ok   the host grants it: "
                          + Low(Flint.Rt.Conc.HostGrant(rt, token, port)));
        Console.WriteLine("  ok   and a duplicate reply is refused: "
                          + Low(Flint.Rt.Conc.HostGrant(rt, token, port)));
        Console.WriteLine("  ok   the runtime end is now: "
                          + StateName(Flint.Rt.Conc.HostPortState(rt, port)));

        // 4. Push something in, let the program read it and answer.
        // ENCODED, because a bridge carries values: the runtime decodes what
        // arrives, so a host writes the wire format rather than raw bytes.
        Console.WriteLine("  ok   delivered: "
            + Low(Flint.Rt.Conc.HostDeliver(rt, port, WireStr("one"))));
        v = Flint.Rt.Conc.Resume(rt);
        Console.WriteLine("  ok   ran on: status " + Status(rt));
        Console.WriteLine("  ok   it sent back: "
                          + Show(new System.Collections.Generic.List<Ev>(Drain(rt))));

        // 5. A second wave, then hang up. Drained-and-closed is END OF STREAM --
        //    `nil` and not an error -- and the program's own `state` call has to
        //    agree with what the host sees.
        Console.WriteLine("  ok   delivered: "
            + Low(Flint.Rt.Conc.HostDeliver(rt, port, WireStr("two"))));
        Flint.Rt.Conc.HostClosePort(rt, port);
        Console.WriteLine("  ok   after the host hangs up: "
                          + StateName(Flint.Rt.Conc.HostPortState(rt, port)));
        v = Flint.Rt.Conc.Resume(rt);
        var tail = new System.Collections.Generic.List<Ev>();
        // The last pump is TWO pumps: exit closes every flint end and pushes an
        // `EV_CLOSED` for each, and a run with events pending comes back 2 -- so
        // a host is never left guessing whether more is coming.
        for (int i = 0; i < 4 && Status(rt) == 2; i++) {
            tail.AddRange(Drain(rt));
            v = Flint.Rt.Conc.Resume(rt);
        }
        Console.WriteLine("  ok   the program answered: " + Rendered(rt, v));
        Console.WriteLine("  ok   status " + Status(rt));
        Console.WriteLine("  ok   and was told the port closed: " + Show(tail));

        // 6. An id the runtime has never heard of. A host treats this as done,
        //    which is what makes the pushed `:closed` an optimisation over
        //    polling rather than the sole carrier of the truth.
        Console.WriteLine("  ok   an unknown port id: "
                          + StateName(Flint.Rt.Conc.HostPortState(rt, 999999)));
        return 0;
    }

    /// `true`/`false` spelled the way Rust and Java spell them. C# capitalises
    /// them, and a transcript that differs only in capitalisation would fail a
    /// comparison that is supposed to be about the protocol.
    private static string Low(bool b) => b ? "true" : "false";

    /// GAS IS A BOUND, on this port too (`doc/decisions/0009`).
    ///
    /// This existed nowhere, and the hole it left is invisible to everything
    /// else: `gasLimit` was a field this runtime wrote into snapshots and never
    /// read, so the native runtime stopped a runaway program and this one ran it
    /// to completion. Conformance cannot catch that -- it diffs ANSWERS, and a
    /// program allowed to run forever eventually produces the right one. A
    /// property about REFUSING has to be tested by asking for the refusal.
    /// The step count for an image, and nothing else -- the CLR half of
    /// `RtSteps`. Two workloads are compared by their DIFFERENCE, so start-up
    /// cancels and only the program's work is left.
    private static int RtSteps(string path) {
        var rt = new Flint.Rt.Rt(1024 * 1024, 64L * 1024 * 1024);
        var img = Flint.Rt.Img.Load(rt, File.ReadAllBytes(path));
        if (img == null) { Console.WriteLine("-1"); return 0; }
        rt.SetGasLimit(0x7ffffff0L);
        foreach (int fn in img.init) rt.Call(rt.MakeClosure(fn, new long[0]), new long[0]);
        rt.RunProgram(rt.MakeClosure(img.entry, new long[0]), new long[]{ Flint.Rt.Val.Nil });
        Console.WriteLine(rt.steps);
        return 0;
    }

    private static int RtGas(string path) {
        int fails = 0;
        void Ok(string label, bool cond, string extra) {
            if (cond) Console.WriteLine("  ok   " + label);
            else { fails++; Console.WriteLine("  FAIL " + label + "\n        " + extra); }
        }
        var rt = new Flint.Rt.Rt(1024 * 1024, 64L * 1024 * 1024);
        var img = Flint.Rt.Img.Load(rt, File.ReadAllBytes(path));
        if (img == null) { Console.WriteLine("  FAIL not a flint image"); return 1; }
        // GENEROUS, not absent: a limit of 0 means "not counting", and an
        // unbudgeted sandbox deliberately maintains no counter.
        rt.SetGasLimit(0x7ffffff0L);
        foreach (int fn in img.init) rt.Call(rt.MakeClosure(fn, new long[0]), new long[0]);
        rt.RunProgram(rt.MakeClosure(img.entry, new long[0]), new long[]{ Flint.Rt.Val.Nil });
        bool threw = !Flint.Rt.Val.IsNil(rt.thrown);
        Ok("with no budget the program runs to its answer", !threw,
            threw ? Flint.Rt.Str.Text(rt, rt.ExMessage(rt.thrown)) : "");
        long spent = rt.steps;
        Ok("  ... and the instructions were counted: " + spent, spent > 1000,
            "steps " + spent + " -- nothing counted, so the bound below proves nothing");

        var rt2 = new Flint.Rt.Rt(1024 * 1024, 64L * 1024 * 1024);
        var img2 = Flint.Rt.Img.Load(rt2, File.ReadAllBytes(path));
        foreach (int fn in img2.init) rt2.Call(rt2.MakeClosure(fn, new long[0]), new long[0]);
        long limit = rt2.steps + spent / 4;
        rt2.SetGasLimit(limit);
        rt2.RunProgram(rt2.MakeClosure(img2.entry, new long[0]), new long[]{ Flint.Rt.Val.Nil });
        bool stopped = !Flint.Rt.Val.IsNil(rt2.thrown);
        Ok("a tight budget stops the program", stopped,
            "it ran to completion on " + limit + " gas when it needs " + spent);
        string msg = stopped ? Flint.Rt.Str.Text(rt2, rt2.ExMessage(rt2.thrown)) : "";
        Ok("  ... with a catchable ResourceExhausted", msg.Contains("gas limit exceeded"), msg);
        Ok("  ... saying what was spent against what was allowed",
            msg.Contains("of " + limit) || msg.Contains("spent"), msg);
        long over = rt2.steps - limit;
        Ok("  ... near the limit rather than far past it (over by " + over + ")",
            over < 200000, "ran " + over + " steps past an exhausted budget");
        if (fails > 0) { Console.WriteLine("gas: " + fails + " FAILURES"); return 1; }
        return 0;
    }

    private static int RtImage(string path, string want) {
        var rt = new Flint.Rt.Rt(1024 * 1024, 64L * 1024 * 1024);
        var img = Flint.Rt.Img.Load(rt, File.ReadAllBytes(path));
        if (img == null) {
            Console.WriteLine("  FAIL not a flint image, or a version this runtime does not speak");
            return 1;
        }
        Console.WriteLine("  ok   loads a real image: " + rt.fns.Length + " fns, " + rt.consts.Length
            + " consts, " + rt.code.Length + " code bytes, entry=" + img.entry
            + ", " + img.nativeNames.Length + " natives, " + img.init.Length + " initialisers");
        // The initialisers first, in order: a program's top-level forms.
        //
        // AND CHECKED. A flint throw is not a host exception -- it sets
        // `thrown` and unwinds to the top, where `Call` returns nil -- so
        // ignoring it means a program whose top-level `assert` FAILED runs on
        // to `main` and reports whatever `main` says.
        foreach (int fn in img.init) {
            rt.Call(rt.MakeClosure(fn, Array.Empty<long>()), Array.Empty<long>());
            if (!Flint.Rt.Val.IsNil(rt.thrown)) { Console.WriteLine("  FAIL " + Why(rt)); return 1; }
        }
        long f = rt.MakeClosure(img.entry, Array.Empty<long>());
        try {
            long v = rt.RunProgram(f, new long[]{ Flint.Rt.Val.Nil });
            if (!Flint.Rt.Val.IsNil(rt.thrown)) { Console.WriteLine("  FAIL " + Why(rt)); return 1; }
            string shown = Flint.Rt.Val.IsFixnum(v)
                    ? Flint.Rt.Val.AsFixnum(v).ToString(System.Globalization.CultureInfo.InvariantCulture)
                : Flint.Rt.Str.IsString(rt, v) ? Flint.Rt.Str.Text(rt, v)
                : Flint.Rt.Val.IsNil(v) ? "nil"
                : "0x" + Convert.ToString(v, 16);
            // TRIMMED on both sides, as in `RtImage`: a shell `$(...)` strips
            // trailing newlines, so the native answer arrives one byte shorter
            // than the guest's own return value. An artifact of how the harness
            // captures the answer, not a disagreement between runtimes.
            if (want != null && want.Trim() != shown.Trim()) {
                Console.WriteLine("  FAIL the ported runtime DISAGREES with the native one");
                Console.WriteLine("        native " + want);
                Console.WriteLine("        ported " + shown);
                return 1;
            }
            Console.WriteLine("  ok   main -> " + shown
                              + (want != null ? "  (the native runtime agrees)" : ""));
        } catch (NotSupportedException e) {
            Console.WriteLine("  .. as far as: " + e.Message);
        }
        return 0;
    }


    // ------------------------------------------------------------------
    // The ported hash, against the numbers a REAL CLOJURE produced.
    //
    // These are not this implementation's own output recorded as a baseline --
    // they are the values in `runtime/src/hash.rs`'s tests, which came out of
    // `bb` 1.3.190 using `clojure.lang.Murmur3`. A test written the other way
    // round would pass for any consistent-but-wrong hash.
    //
    // A mirror of the JVM's `RtHash.java`, printing the same lines so the gate
    // can compare them.

    static int hashFails;

    static byte[] HB(string s) => System.Text.Encoding.UTF8.GetBytes(s);

    static void HEq(string what, int got, int want) {
        if (got != want) {
            Console.WriteLine("  FAIL " + what + ": got " + got + ", clojure says " + want);
            hashFails++;
        }
    }

    private static int RtHash() {
        HEq("(hash 0)", _3sln.Flint.Kgen.Rt.Hash.HashLong(0), 0);
        HEq("(hash 1)", _3sln.Flint.Kgen.Rt.Hash.HashLong(1), 1392991556);
        HEq("(hash -1)", _3sln.Flint.Kgen.Rt.Hash.HashLong(-1), 1651860712);
        HEq("(hash 42)", _3sln.Flint.Kgen.Rt.Hash.HashLong(42), 1871679806);
        HEq("(hash 12345678901234)", _3sln.Flint.Kgen.Rt.Hash.HashLong(12345678901234L), -1096982217);
        HEq("(hash Long/MAX_VALUE)", _3sln.Flint.Kgen.Rt.Hash.HashLong(long.MaxValue), -2106506049);
        HEq("(hash Long/MIN_VALUE)", _3sln.Flint.Kgen.Rt.Hash.HashLong(long.MinValue), 1366273829);
        Console.WriteLine("  ok   longs hash as Clojure hashes them");

        HEq("(hash 0.0)", Flint.Rt.Hash.HashDouble(0.0), 0);
        HEq("(hash -0.0)", Flint.Rt.Hash.HashDouble(-0.0), 0);
        HEq("(hash 1.0)", Flint.Rt.Hash.HashDouble(1.0), 1072693248);
        HEq("(hash 1.5)", Flint.Rt.Hash.HashDouble(1.5), 1073217536);
        HEq("(hash -2.75)", Flint.Rt.Hash.HashDouble(-2.75), -1073348608);
        Console.WriteLine("  ok   doubles too, and -0.0 hashes as 0.0");

        HEq("(hash \"\")", Flint.Rt.Hash.HashString(HB("")), 0);
        HEq("(hash \"a\")", Flint.Rt.Hash.HashString(HB("a")), 1455541201);
        HEq("(hash \"abc\")", Flint.Rt.Hash.HashString(HB("abc")), 74834163);
        HEq("(hash \"hello, world\")", Flint.Rt.Hash.HashString(HB("hello, world")), 136167191);
        HEq("(hash \"日本語\")", Flint.Rt.Hash.HashString(HB("日本語")), 1333041691);
        Console.WriteLine("  ok   strings, including non-ASCII over UTF-16 units");

        HEq("(hash 'a)", Flint.Rt.Hash.HashSymbol(null, HB("a")), -482876059);
        HEq("(hash 'abc)", Flint.Rt.Hash.HashSymbol(null, HB("abc")), 408495850);
        HEq("(hash 'foo/bar)", Flint.Rt.Hash.HashSymbol(HB("foo"), HB("bar")), 254379989);
        HEq("(hash :a)", Flint.Rt.Hash.HashKeyword(null, HB("a")), -2123407586);
        HEq("(hash :abc)", Flint.Rt.Hash.HashKeyword(null, HB("abc")), -1232035677);
        HEq("(hash :foo/bar)", Flint.Rt.Hash.HashKeyword(HB("foo"), HB("bar")), -1386151538);
        Console.WriteLine("  ok   symbols and keywords, namespace asymmetry included");

        // A surrogate pair must count as TWO units. If it counted as one the
        // string hash would still be stable and still be wrong, which is
        // exactly the failure this case exists to catch.
        int[] u = Flint.Rt.Hash.Utf16Test(HB("\U0001F600"));
        HEq("an emoji is two UTF-16 units", u.Length, 2);
        HEq("  high surrogate", u[0], 0xD83D);
        HEq("  low surrogate", u[1], 0xDE00);
        Console.WriteLine("  ok   an astral-plane character is a surrogate PAIR");

        if (hashFails > 0) { Console.WriteLine("  " + hashFails + " failed"); return 1; }
        return 0;
    }


    // ------------------------------------------------------------------
    // Maps on the ported runtime. A line-for-line mirror of the JVM's
    // `RtMaps.java`, printing the same lines so the gate can compare them.

    static int mapFails;

    static void MOk(string what, bool cond) {
        Console.WriteLine((cond ? "  ok   " : "  FAIL ") + what);
        if (!cond) mapFails++;
    }

    static long K(Flint.Rt.Rt rt, int i) => Flint.Rt.Val.Fixnum(i);

    static bool AllPresent(Flint.Rt.Rt rt, long m, int from, int to) {
        for (int i = from; i < to; i++) {
            long got = _3sln.Flint.Kgen.Rt.Mapread.MapGet(rt, m, Flint.Rt.Val.Fixnum(i), Flint.Rt.Val.Nil);
            if (!Flint.Rt.Val.IsFixnum(got) || Flint.Rt.Val.AsFixnum(got) != i * 10L) return false;
        }
        return true;
    }

    private static int RtMaps() {

    var rt = new Flint.Rt.Rt(4 * 1024 * 1024, 128L * 1024 * 1024);

    // --- the array-map, and the boundary.
    int bas = rt.Mark();
    int m = rt.Push(Flint.Rt.Maps.Empty(rt));
    MOk("an empty map counts 0", _3sln.Flint.Kgen.Rt.Mapcore.MapCount(rt, rt.R(m)) == 0);
    for (int i = 0; i < 8; i++) rt.SetR(m, _3sln.Flint.Kgen.Rt.Mapwrite.MapAssoc(rt, rt.R(m), K(rt, i), Flint.Rt.Val.Fixnum(i * 10)));
    MOk("8 entries is still a flat array-map", _3sln.Flint.Kgen.Rt.Mapcore.IsArrayMap(rt, rt.R(m)));
    MOk("  and every one reads back", AllPresent(rt, rt.R(m), 0, 8));
    rt.SetR(m, _3sln.Flint.Kgen.Rt.Mapwrite.MapAssoc(rt, rt.R(m), K(rt, 8), Flint.Rt.Val.Fixnum(80)));
    MOk("the 9th promotes to a CHAMP trie", !_3sln.Flint.Kgen.Rt.Mapcore.IsArrayMap(rt, rt.R(m)) && _3sln.Flint.Kgen.Rt.Mapcore.IsMap(rt, rt.R(m)));
    MOk("  and nothing was lost crossing the boundary", AllPresent(rt, rt.R(m), 0, 9));

    // --- a big map, in and out.
    const int N = 2000;
    rt.SetR(m, Flint.Rt.Maps.Empty(rt));
    for (int i = 0; i < N; i++) rt.SetR(m, _3sln.Flint.Kgen.Rt.Mapwrite.MapAssoc(rt, rt.R(m), K(rt, i), Flint.Rt.Val.Fixnum(i * 10)));
    MOk(N + " keys, all present, count agrees", _3sln.Flint.Kgen.Rt.Mapcore.MapCount(rt, rt.R(m)) == N && AllPresent(rt, rt.R(m), 0, N));
    MOk("a key that was never added is absent",
       _3sln.Flint.Kgen.Rt.Mapread.MapGet(rt, rt.R(m), Flint.Rt.Val.Fixnum(-1), Flint.Rt.Val.Nil) == Flint.Rt.Val.Nil);
    // Re-assoc with the same value must not grow the map.
    rt.SetR(m, _3sln.Flint.Kgen.Rt.Mapwrite.MapAssoc(rt, rt.R(m), K(rt, 5), Flint.Rt.Val.Fixnum(50)));
    MOk("re-assoc with an identical value does not grow it", _3sln.Flint.Kgen.Rt.Mapcore.MapCount(rt, rt.R(m)) == N);

    // --- CANONICAL FORM: the property CHAMP is chosen for.
    int viaDelete = rt.Push(rt.R(m));
    for (int i = 0; i < N; i += 2) {
      rt.SetR(viaDelete, _3sln.Flint.Kgen.Rt.Mapwrite.MapDissoc(rt, rt.R(viaDelete), K(rt, i)));
    }
    int direct = rt.Push(Flint.Rt.Maps.Empty(rt));
    for (int i = 1; i < N; i += 2) {
      rt.SetR(direct, _3sln.Flint.Kgen.Rt.Mapwrite.MapAssoc(rt, rt.R(direct), K(rt, i), Flint.Rt.Val.Fixnum(i * 10)));
    }
    MOk("built-by-deleting and built-directly have the same count",
       _3sln.Flint.Kgen.Rt.Mapcore.MapCount(rt, rt.R(viaDelete)) == _3sln.Flint.Kgen.Rt.Mapcore.MapCount(rt, rt.R(direct)));
    MOk("  ... and are =", Flint.Rt.Maps.Eq(rt, rt.R(viaDelete), rt.R(direct)));
    MOk("  ... and HASH ALIKE, which is what canonical form means",
       global::_3sln.Flint.Kgen.Rt.Valhash.HashValue(rt, rt.R(viaDelete)) == global::_3sln.Flint.Kgen.Rt.Valhash.HashValue(rt, rt.R(direct)));
    MOk("  ... and the deleted keys really are gone",
       _3sln.Flint.Kgen.Rt.Mapread.MapGet(rt, rt.R(viaDelete), K(rt, 0), Flint.Rt.Val.Nil) == Flint.Rt.Val.Nil
       && _3sln.Flint.Kgen.Rt.Mapread.MapGet(rt, rt.R(viaDelete), K(rt, 2), Flint.Rt.Val.Nil) == Flint.Rt.Val.Nil);

    // --- COLLISIONS. Not with integer keys: `hashLong` on a small long is a
    // BIJECTION on 32 bits -- `mixK1`, `mixH1` and `fmix` are each invertible,
    // and with the high word zero the whole pipeline is -- so sequential
    // integers NEVER collide. (Searched 300,000 of them and found nothing,
    // which is what sent me looking for the reason.) That is a real property
    // worth knowing: an integer-keyed map never reaches the collision path.
    //
    // Strings do collide, and the classic Java pair is the honest instrument:
    // "Aa" and "BB" have the same `String.hashCode`, so they have the same
    // flint hash too. Asserted rather than assumed, because the day the hash
    // changes this test should say so instead of quietly testing nothing.
    byte[] aa = System.Text.Encoding.UTF8.GetBytes("Aa");
    byte[] bb = System.Text.Encoding.UTF8.GetBytes("BB");
    MOk("\"Aa\" and \"BB\" collide, as they do in Java",
       Flint.Rt.Hash.HashString(aa) == Flint.Rt.Hash.HashString(bb));
    {
      int c = rt.Push(Flint.Rt.Maps.Empty(rt));
      // Padded past the array-map, or the collision never reaches a trie node
      // and the collision-node code is not what is being exercised.
      for (int i = 0; i < 20; i++) rt.SetR(c, _3sln.Flint.Kgen.Rt.Mapwrite.MapAssoc(rt, rt.R(c), Flint.Rt.Val.Fixnum(1000000 + i), Flint.Rt.Val.Fixnum(i)));
      int ka = rt.Push(Flint.Rt.Str.Of(rt, "Aa"));
      int kb = rt.Push(Flint.Rt.Str.Of(rt, "BB"));
      rt.SetR(c, _3sln.Flint.Kgen.Rt.Mapwrite.MapAssoc(rt, rt.R(c), rt.R(ka), Flint.Rt.Val.Fixnum(111)));
      rt.SetR(c, _3sln.Flint.Kgen.Rt.Mapwrite.MapAssoc(rt, rt.R(c), rt.R(kb), Flint.Rt.Val.Fixnum(222)));
      MOk("both colliding keys are stored and distinct",
         Flint.Rt.Val.AsFixnum(_3sln.Flint.Kgen.Rt.Mapread.MapGet(rt, rt.R(c), rt.R(ka), Flint.Rt.Val.Nil)) == 111
         && Flint.Rt.Val.AsFixnum(_3sln.Flint.Kgen.Rt.Mapread.MapGet(rt, rt.R(c), rt.R(kb), Flint.Rt.Val.Nil)) == 222);
      MOk("  and the count counts them both", _3sln.Flint.Kgen.Rt.Mapcore.MapCount(rt, rt.R(c)) == 22);
      rt.SetR(c, _3sln.Flint.Kgen.Rt.Mapwrite.MapDissoc(rt, rt.R(c), rt.R(ka)));
      MOk("  removing one leaves the other",
         _3sln.Flint.Kgen.Rt.Mapread.MapGet(rt, rt.R(c), rt.R(ka), Flint.Rt.Val.Nil) == Flint.Rt.Val.Nil
         && Flint.Rt.Val.AsFixnum(_3sln.Flint.Kgen.Rt.Mapread.MapGet(rt, rt.R(c), rt.R(kb), Flint.Rt.Val.Nil)) == 222);
      MOk("  and the collision node collapsed back to an inline entry",
         _3sln.Flint.Kgen.Rt.Mapcore.MapCount(rt, rt.R(c)) == 21);
    }

    // --- keys that are not fixnums, and a collection under collection.
    int s = rt.Push(Flint.Rt.Maps.Empty(rt));
    rt.SetR(s, _3sln.Flint.Kgen.Rt.Mapwrite.MapAssoc(rt, rt.R(s), Flint.Rt.Str.Of(rt, "hello, world"), Flint.Rt.Val.Fixnum(1)));
    rt.SetR(s, _3sln.Flint.Kgen.Rt.Mapwrite.MapAssoc(rt, rt.R(s), Flint.Rt.Str.Keyword(rt, null, "kw"), Flint.Rt.Val.Fixnum(2)));
    int vk = rt.Push(Flint.Rt.Vec.Empty(rt));
    rt.SetR(vk, Flint.Rt.Vec.Conj(rt, rt.R(vk), Flint.Rt.Val.Fixnum(7)));
    rt.SetR(s, _3sln.Flint.Kgen.Rt.Mapwrite.MapAssoc(rt, rt.R(s), rt.R(vk), Flint.Rt.Val.Fixnum(3)));
    // A SEPARATE but equal key must find the same entry -- that is the whole
    // difference between `=` and identity, and where interning would be a
    // shortcut rather than the answer.
    int vk2 = rt.Push(Flint.Rt.Vec.Empty(rt));
    rt.SetR(vk2, Flint.Rt.Vec.Conj(rt, rt.R(vk2), Flint.Rt.Val.Fixnum(7)));
    MOk("a string key reads back",
       Flint.Rt.Val.AsFixnum(_3sln.Flint.Kgen.Rt.Mapread.MapGet(rt, rt.R(s), Flint.Rt.Str.Of(rt, "hello, world"), Flint.Rt.Val.Nil)) == 1);
    MOk("an equal-but-separate VECTOR key finds the same entry",
       Flint.Rt.Val.AsFixnum(_3sln.Flint.Kgen.Rt.Mapread.MapGet(rt, rt.R(s), rt.R(vk2), Flint.Rt.Val.Nil)) == 3);

    // --- the collector, over all of it.
    rt.gc.Major(rt.roots);
    MOk("everything survives a major collection", AllPresent(rt, rt.R(m), 0, N)
       && Flint.Rt.Maps.Eq(rt, rt.R(viaDelete), rt.R(direct)));

    rt.PopTo(bas);
        if (mapFails > 0) { Console.WriteLine("  " + mapFails + " failed"); return 1; }
        return 0;
    }


    // ------------------------------------------------------------------
    // SHELVING A REAL PROGRAM, mid-run. A mirror of the JVM's `RtShelve.java`,
    // printing the same lines so the gate can compare them.

    static int shelveFails;

    static void SOk(string what, bool cond) {
        Console.WriteLine((cond ? "  ok   " : "  FAIL ") + what);
        if (!cond) shelveFails++;
    }

    static string SShow(Flint.Rt.Rt rt, long v) =>
        Flint.Rt.Val.IsFixnum(v) ? Flint.Rt.Val.AsFixnum(v).ToString(System.Globalization.CultureInfo.InvariantCulture)
      : Flint.Rt.Str.IsString(rt, v) ? Flint.Rt.Str.Text(rt, v)
      : Flint.Rt.Val.IsNil(v) ? "nil" : "0x" + Convert.ToString(v, 16);

    static string STrim(string s) => s.Length > 60 ? s.Substring(0, 60) + "..." : s;

    /// Run until the step budget trips, leaving the runtime mid-program.
    static bool RunUntilPaused(Flint.Rt.Rt rt, Flint.Rt.Img.Loaded img, long budget) {
        foreach (int fn in img.init)
            rt.Call(rt.MakeClosure(fn, Array.Empty<long>()), Array.Empty<long>());
        long f = rt.MakeClosure(img.entry, Array.Empty<long>());
        rt.SetSliceEnd(budget);
        rt.Call(f, new long[]{ Flint.Rt.Val.Nil });
        return rt.Parked();
    }

    private static int RtShelve(string path, string wantArg) {

    byte[] image = File.ReadAllBytes(path);
    string want = wantArg;

    // What the program says when nothing interrupts it.
    var plain = new Flint.Rt.Rt(4 * 1024 * 1024, 128L * 1024 * 1024);
    Flint.Rt.Img.Loaded pimg = Flint.Rt.Img.Load(plain, image);
    foreach (int fn in pimg.init) plain.Call(plain.MakeClosure(fn, new long[0]), new long[0]);
    string straight = SShow(plain, plain.Call(plain.MakeClosure(pimg.entry, new long[0]),
                                             new long[]{ Flint.Rt.Val.Nil }));
    SOk("the program runs straight through to " + STrim(straight), straight == want);

    foreach (string kind in new[]{ "verbatim", "live" }) {
      var rt = new Flint.Rt.Rt(4 * 1024 * 1024, 128L * 1024 * 1024);
      Flint.Rt.Img.Loaded img = Flint.Rt.Img.Load(rt, image);
      // Partway: enough to be deep in the program, not enough to finish. The
      // budget is a fraction of what the program takes, so the pause lands
      // inside a loop with frames live rather than at a tidy boundary.
      bool paused = RunUntilPaused(rt, img, 5000);
      SOk(kind + ": stopped mid-program with " + rt.frames.Count + " frames live", paused);

      byte[] snap = kind == "verbatim" ? Flint.Rt.Snap.Capture(rt) : Flint.Rt.Snap.ExportLive(rt);
      SOk(kind + ": snapshot taken of a half-finished program (" + snap.Length + " bytes)",
         snap != null && snap.Length > 0);

      // A FRESH runtime. The image is loaded first, because a snapshot carries
      // no code -- that is the whole point of the fingerprint, and it is what
      // makes the snapshot small enough to move.
      var back = new Flint.Rt.Rt(kind == "live" ? 7 * 1024 * 1024 : 4 * 1024 * 1024,
                       128L * 1024 * 1024);
      Flint.Rt.Img.Load(back, image);
      bool took = kind == "verbatim" ? Flint.Rt.Snap.Restore(back, snap)
                                             : Flint.Rt.Snap.ImportLive(back, snap);
      SOk(kind + ": the fresh runtime accepts it", took);

      // And carries on. `run(0)` resumes the frames the snapshot restored --
      // there is no entry point to call, because the program is already inside
      // itself.
      // `resume`, not `run`: a snapshot is FAITHFUL, so `thrown` comes back
      // holding the park that was in flight when it was taken. Ending a
      // slice pause is what resuming means, and it is what the scheduler's
      // `settle` does for a program that never stopped.
      back.SetSliceEnd(0);
      string finished = SShow(back, back.Resume());
      SOk(kind + ": it carries on to the SAME answer", finished == want);
      if (finished != want) {
        Console.WriteLine("        wanted " + STrim(want));
        Console.WriteLine("        got    " + STrim(finished));
      }
    }

    // A snapshot of one program must not load into another. Refused by
    // FINGERPRINT, because every `ip` and constant index in it is an index into
    // the image it came from -- so the wrong image does not fail, it quietly
    // means something else.
    var other = new Flint.Rt.Rt(4 * 1024 * 1024, 128L * 1024 * 1024);
    Flint.Rt.Img.Load(other, image);
    other.fingerprint ^= 1;
    SOk("a snapshot is refused by a runtime holding a different program",
       !Flint.Rt.Snap.Restore(other, Flint.Rt.Snap.Capture(plain)));
        if (shelveFails > 0) { Console.WriteLine("  " + shelveFails + " failed"); return 1; }
        return 0;
    }


    // ------------------------------------------------------------------
    /// The flint COMPILER, on the ported runtime. A mirror of the JVM's
    /// `RtSelfHost.java`, printing the same lines so the gate can compare them.
    ///
    /// Checked against the wasm compiler's OUTPUT, byte for byte. "It ran"
    /// would have passed with every map literal empty, which is one of the four
    /// bugs this found.
    private static int RtSelfHost(string specPath, string refPath) {
        var rt = new Flint.Rt.Rt(64L * 1024 * 1024, 2048L * 1024 * 1024);
        var img = Flint.Rt.Img.Load(rt, File.ReadAllBytes("dist/flintc.bytecode"));
        if (img == null) { Console.WriteLine("  FAIL not an image"); return 1; }
        Console.WriteLine("  the compiler: " + rt.fns.Length + " fns, " + rt.consts.Length
            + " consts, " + rt.code.Length + " code bytes, " + img.nativeNames.Length + " natives");
        int missing = 0;
        var names = new System.Text.StringBuilder();
        for (int i = 0; i < img.nativeNames.Length; i++) {
            if (rt.natives[i] == null) { missing++; if (missing <= 60) names.Append(" ").Append(img.nativeNames[i]); }
        }
        Console.WriteLine("  builtins it wants that this runtime lacks: " + missing + names);
        foreach (int fn in img.init)
            rt.Call(rt.MakeClosure(fn, Array.Empty<long>()), Array.Empty<long>());
        Console.WriteLine("  ok   " + img.init.Length + " initialisers ran");

        // `flint.selfhost/main` is a VAR, not a named entry in the function
        // table. The initialisers bind it, which is why they have to run first.
        int slot = -1;
        for (int i = 0; i < img.varNames.Length; i++)
            if (Flint.Rt.Str.Text(rt, rt.consts[img.varNames[i]]) == "flint.selfhost/main") slot = i;
        if (slot < 0) { Console.WriteLine("  FAIL flint.selfhost/main is not in the var table"); return 1; }
        long compiler = rt.roots.shared.Globals[slot];
        if (Flint.Rt.Val.IsNil(compiler)) {
            Console.WriteLine("  FAIL flint.selfhost/main is unbound after the initialisers");
            return 1;
        }
        Console.WriteLine("  ok   flint.selfhost/main is bound: " + rt.Describe(compiler));

        // ROOTED, and passed as an ARGV: `main` dispatches on the FIRST element.
        int bas = rt.Mark();
        int ci = rt.Push(compiler);
        int si = rt.Push(Flint.Rt.Str.Of(rt, File.ReadAllText(specPath)));
        int li = rt.Push(Flint.Rt.Seqs.FromRoots(rt, si, 1));
        long outv = rt.RunProgram(rt.R(ci), new long[]{ rt.R(li) });
        string s = Flint.Rt.Str.IsString(rt, outv) ? Flint.Rt.Str.Text(rt, outv)
                 : "NOT A STRING: " + rt.Describe(outv);
        rt.PopTo(bas);
        Console.WriteLine("  ok   the compiler ran and produced " + s.Length + " chars");
        if (!Flint.Rt.Val.IsNil(rt.thrown)) {
            Console.WriteLine("  FAIL the compiler threw: " + rt.Describe(rt.thrown));
            return 1;
        }
        string want = File.ReadAllText(refPath);
        if (s != want) {
            Console.WriteLine("  FAIL the output differs from the wasm compiler's ("
                              + s.Length + " vs " + want.Length + " chars)");
            return 1;
        }
        Console.WriteLine("  ok   and it is byte for byte what the wasm compiler emits");
        return 0;
    }


    // ------------------------------------------------------------------
    /// Two executors, one heap (`doc/decisions/0028`). A mirror of the JVM's
    /// `RtParallel.java`, printing the same lines so the gate can compare them.
    ///
    /// The claim is not "it does not crash". It is that a collection staged by
    /// one thread walks the OTHER thread's roots, so objects that thread is
    /// holding survive and the ones it moved are found where they moved to.
    ///
    /// These are REAL HOST THREADS sharing one flint heap -- a different thing
    /// from the green threads in `Conc`, and both exist: green threads are how
    /// one sandbox interleaves its own work deterministically, and this is how
    /// a host drives one sandbox with a pool.

    static int parFails;

    static void POk(string what, bool cond) {
        Console.WriteLine((cond ? "  ok   " : "  FAIL ") + what);
        if (!cond) parFails++;
    }

    /// Build `n` cons cells and read every one back. Everything stays on the
    /// ROOT STACK across the allocations, which is the discipline that has to
    /// hold across ANOTHER thread's collection now too.
    static int Churn(Flint.Rt.Rt rt, long tag, int n) {
        // From here this thread can be stopped for a collection; after
        // `LeaveGuest` nothing waits for it. Getting this bracket wrong is not
        // a subtle bug: the Rust's first version without it HUNG.
        rt.EnterGuest();
        int bas = rt.Mark();
        for (int i = 0; i < n; i++)
            rt.Push(Flint.Rt.Seqs.Cons(rt, Flint.Rt.Val.Fixnum(tag * 1_000_000 + i), Flint.Rt.Val.Nil));
        // A cell whose pointer was not fixed up after a move reads as the WRONG
        // NUMBER here, rather than crashing. That is the failure worth
        // catching: a crash would at least be obvious.
        for (int i = 0; i < n; i++) {
            long got = global::_3sln.Flint.Kgen.Rt.Seqwalk.First(rt, rt.R(bas + i));
            if (!Flint.Rt.Val.IsFixnum(got) || Flint.Rt.Val.AsFixnum(got) != tag * 1_000_000 + i)
                throw new System.InvalidOperationException(
                    "thread " + tag + ": slot " + i + " came back wrong: " + rt.Describe(got));
        }
        rt.PopTo(bas);
        rt.LeaveGuest();
        return n;
    }

    private static int RtParallel() {
        var primary = new Flint.Rt.Rt(64 * 1024, 256L * 1024 * 1024);
        var secondary = primary.Executor();
        POk("a second executor registers", primary.roots.shared.par.Executors() == 2);

        const int n = 20_000;
        System.Exception err = null;
        var t = new System.Threading.Thread(() => {
            try { Churn(secondary, 2, n); } catch (System.Exception e) { err = e; }
        });
        t.Start();
        Churn(primary, 1, n);
        t.Join();
        if (err != null) { Console.WriteLine("  FAIL the second executor: " + err); return 1; }
        POk("two executors churned " + n + " cells each on one heap", true);

        // Both really did collect, or the test proved nothing about collection.
        // This says HOW MANY, so a change that quietly stops the nursery
        // filling shows up as a weaker test rather than as a passing one.
        long minor = primary.gc.minors;
        POk("and the nursery was under real pressure: " + minor + " collections", minor >= 4);
        secondary.Close();

        var lone = new Flint.Rt.Rt(64 * 1024, 64L * 1024 * 1024);
        POk("a lone sandbox has one executor and polls nothing",
            lone.roots.shared.par.Executors() == 1 && !lone.safepoints);

        // Two threads interning the same text must get ONE object, or `=` on
        // two interned strings -- a pointer compare -- answers false for equal
        // values.
        var p2 = new Flint.Rt.Rt(1024 * 1024, 128L * 1024 * 1024);
        var s2 = p2.Executor();
        long[] got2 = new long[2];
        // Longer than InlineMax (5) so it is on the heap, and no longer than
        // InternMax (32) so it is INTERNED. Outside that window the test would
        // pass or fail for the wrong reason.
        const string text = "interned-across-executors";
        var t2 = new System.Threading.Thread(() => {
            s2.EnterGuest();
            int b = s2.Mark();
            s2.Push(Flint.Rt.Str.Of(s2, text));
            got2[1] = s2.R(b);
            s2.PopTo(b);
            s2.LeaveGuest();
        });
        t2.Start();
        p2.EnterGuest();
        int b2 = p2.Mark();
        p2.Push(Flint.Rt.Str.Of(p2, text));
        got2[0] = p2.R(b2);
        p2.PopTo(b2);
        p2.LeaveGuest();
        t2.Join();
        POk("the text is in the interned window: " + text.Length + " bytes",
            text.Length > Flint.Rt.Val.InlineMax && text.Length <= Flint.Rt.Interns.InternMax);
        POk("two executors interning one text agree on ONE object", got2[0] == got2[1]);
        s2.Close();

        if (parFails > 0) { Console.WriteLine("  " + parFails + " failed"); return 1; }
        return 0;
    }

    /// THE STALE-PUSH CHECK, CHECKED. The JVM's `RtStale` says the same thing
    /// in the same order; see it for why this is worth a mode of its own.
    ///
    /// Two questions, because neither is worth much alone: does the check fire
    /// on a value carried across a collection, and does it stay quiet on one
    /// that was rooted properly. A check that fires on everything is worse
    /// than no check.
    private static int RtStale() {
        if (!Flint.Rt.Rt.StaleCheck) {
            Console.WriteLine("  FAIL the stale check is not enabled (set FLINT_STALE)");
            return 1;
        }
        // GOOD: rooted before anything allocates.
        var rt = new Flint.Rt.Rt(64 * 1024, 1024L * 1024);
        int bse = rt.Mark();
        int vi = rt.Push(Flint.Rt.Str.Of(rt, "a value that lives on the heap"));
        for (int i = 0; i < 20000; i++) Flint.Rt.Str.Of(rt, "filler " + i);
        rt.Push(rt.R(vi));
        rt.PopTo(bse);
        if (Flint.Rt.Rt.StaleCount != 0) {
            Console.WriteLine("  FAIL a correctly rooted value tripped the stale check");
            return 1;
        }
        Console.WriteLine("  ok   a rooted value survives 20000 allocations quietly");

        // BAD: the same value in a HOST LOCAL across the same allocations.
        var rt2 = new Flint.Rt.Rt(64 * 1024, 1024L * 1024);
        int b2 = rt2.Mark();
        long v = Flint.Rt.Str.Of(rt2, "a value that lives on the heap");
        for (int i = 0; i < 20000; i++) Flint.Rt.Str.Of(rt2, "filler " + i);
        rt2.Push(v);
        rt2.PopTo(b2);
        if (Flint.Rt.Rt.StaleCount == 0) {
            Console.WriteLine("  FAIL the stale check did NOT fire on a stale push");
            return 1;
        }
        Console.WriteLine("  ok   a value carried across a collection is caught");
        return 0;
    }

    private static int RtFoundation() {
        foreach (long off in new long[]{8, 16, 0x1000, 0xFFFF_FFFFL, 0x1_0000_0000L,
                                        0x0000_FFFF_FFFF_FFF8L}) {
            long v = Flint.Rt.Val.Heap(off);
            if (!Flint.Rt.Val.IsHeap(v) || Flint.Rt.Val.AsHeap(v) != off) {
                Console.WriteLine($"  FAIL heap round trip at {off:x}");
                return 1;
            }
        }
        foreach (long n in new long[]{0, 1, -1, 1L << 40, -(1L << 40)}) {
            if (Flint.Rt.Val.AsFixnum(Flint.Rt.Val.Fixnum(n)) != n) {
                Console.WriteLine($"  FAIL fixnum round trip at {n}");
                return 1;
            }
        }
        Console.WriteLine("  ok   values round-trip across 48 bits");

        using var sp = new Flint.Rt.Space(64L * 1024 * 1024);
        long addr = sp.Take(1024);
        Flint.Rt.Obj.WriteHeader(sp, addr, Flint.Rt.Obj.TyCons, 4);
        if (Flint.Rt.Obj.Ty(sp, addr) != Flint.Rt.Obj.TyCons || Flint.Rt.Obj.Len(sp, addr) != 4) {
            Console.WriteLine("  FAIL header round trip"); return 1;
        }
        Flint.Rt.Obj.SetSlotRaw(sp, addr, 0, Flint.Rt.Val.Fixnum(42));
        if (Flint.Rt.Val.AsFixnum(Flint.Rt.Obj.Slot(sp, addr, 0)) != 42) {
            Console.WriteLine("  FAIL slot round trip"); return 1;
        }
        Flint.Rt.Obj.SetMarked(sp, addr, true);
        if (!Flint.Rt.Obj.Marked(sp, addr) || Flint.Rt.Obj.Ty(sp, addr) != Flint.Rt.Obj.TyCons) {
            Console.WriteLine("  FAIL the mark bit disturbed the type"); return 1;
        }
        long far = 0x0000_FF00_1234_5678L;
        Flint.Rt.Obj.SetForward(sp, addr, far);
        if (Flint.Rt.Obj.ForwardTarget(sp, addr) != far) {
            Console.WriteLine("  FAIL a 48-bit forward did not survive the header"); return 1;
        }
        Console.WriteLine("  ok   objects, mark bits and 48-bit forwarding");

        GcStress();
        Interpreter();

        long stack = sp.Take(1024);
        long best = long.MaxValue;
        var sw = new System.Diagnostics.Stopwatch();
        for (int rep = 0; rep < 7; rep++) {
            sp.WriteU64(stack, Flint.Rt.Val.Fixnum(0));
            sp.WriteU64(stack + 8, Flint.Rt.Val.Fixnum(0));
            sw.Restart();
            for (int k = 0; k < 3_000_000; k++) {
                long i = Flint.Rt.Val.AsFixnum(sp.ReadU64(stack));
                long acc = Flint.Rt.Val.AsFixnum(sp.ReadU64(stack + 8));
                sp.WriteU64(stack, Flint.Rt.Val.Fixnum(i + 1));
                sp.WriteU64(stack + 8, Flint.Rt.Val.Fixnum(acc + i));
            }
            sw.Stop();
            best = System.Math.Min(best, sw.Elapsed.Ticks * 100);
        }
        double ns = best / 3_000_000.0;
        Console.WriteLine($"    3,000,000 iterations, best of 7: {ns:F2} ns/iteration");
        Console.WriteLine($"    against 22 ns boxed on the current port -- {22.0 / ns:F0}x");
        return 0;
    }

    /// The interpreter, on hand-assembled bytecode. The SAME three checks as
    /// `runtimes/jvm/test/RtFoundation.java`, in the same order.
    ///
    /// Hand-assembled rather than loaded from an image, so this tests the
    /// dispatch loop and nothing else: the image loader and the builtins are
    /// not ported yet, and a failure here would otherwise have three possible
    /// causes instead of one.
    private static void Interpreter() {
        using var rt = new Flint.Rt.Rt(256 * 1024, 16L * 1024 * 1024);

        var a = new Asm();
        a.Op(Flint.Rt.Op.Int).I16(0).Op(Flint.Rt.Op.SetLocal).U8(1);
        a.Op(Flint.Rt.Op.Int).I16(0).Op(Flint.Rt.Op.SetLocal).U8(2);
        int top = a.At();
        a.Op(Flint.Rt.Op.Local).U8(1).Op(Flint.Rt.Op.Local).U8(0).Op(Flint.Rt.Op.LtInt);
        int exit = a.Op(Flint.Rt.Op.JumpIfFalse).Hole();
        a.Op(Flint.Rt.Op.Local).U8(2).Op(Flint.Rt.Op.Local).U8(1).Op(Flint.Rt.Op.AddInt).Op(Flint.Rt.Op.SetLocal).U8(2);
        a.Op(Flint.Rt.Op.Local).U8(1).Op(Flint.Rt.Op.Int).I16(1).Op(Flint.Rt.Op.AddInt).Op(Flint.Rt.Op.SetLocal).U8(1);
        a.JumpTo(top);
        a.Patch(exit);
        a.Op(Flint.Rt.Op.Local).U8(2).Op(Flint.Rt.Op.Return);

        rt.code = a.Done();
        rt.fns = new[]{ new Flint.Rt.Rt.FnDef(
            new[]{ new Flint.Rt.Rt.Arity(1, false, 3, 0, rt.code.Length) }, 0) };
        long f = rt.MakeClosure(0, System.Array.Empty<long>());
        long got = rt.Call(f, new[]{ Flint.Rt.Val.Fixnum(1000) });
        long want = 1000L * 999 / 2;
        if (Flint.Rt.Val.AsFixnum(got) != want)
            throw new System.Exception($"loop gave {Flint.Rt.Val.AsFixnum(got)} want {want}");
        Console.WriteLine($"  ok   the interpreter runs a counting loop ({Flint.Rt.Val.AsFixnum(got):N0} in {rt.steps:N0} steps)");

        var b = new Asm();
        b.Op(Flint.Rt.Op.Closure).U16(0).U8(0);
        b.Op(Flint.Rt.Op.Local).U8(0);
        b.Op(Flint.Rt.Op.Call).U8(1);
        b.Op(Flint.Rt.Op.Int).I16(1).Op(Flint.Rt.Op.AddInt).Op(Flint.Rt.Op.Return);
        byte[] second = b.Done();
        int off = rt.code.Length;
        var both = new byte[off + second.Length];
        System.Array.Copy(rt.code, both, off);
        System.Array.Copy(second, 0, both, off, second.Length);
        rt.code = both;
        rt.fns = new[]{
            new Flint.Rt.Rt.FnDef(new[]{ new Flint.Rt.Rt.Arity(1, false, 3, 0, off) }, 0),
            new Flint.Rt.Rt.FnDef(new[]{ new Flint.Rt.Rt.Arity(1, false, 2, off, second.Length) }, 0),
        };
        long g = rt.MakeClosure(1, System.Array.Empty<long>());
        long got2 = rt.Call(g, new[]{ Flint.Rt.Val.Fixnum(100) });
        if (Flint.Rt.Val.AsFixnum(got2) != 100L * 99 / 2 + 1)
            throw new System.Exception($"nested call gave {Flint.Rt.Val.AsFixnum(got2)}");
        Console.WriteLine("  ok     ... and a call and return across frames");

        // The comparison is EXPLICIT: 0 is truthy in Clojure, so branching on
        // `n` itself would never terminate.
        var c = new Asm();
        c.Op(Flint.Rt.Op.Local).U8(0).Op(Flint.Rt.Op.Int).I16(0).Op(Flint.Rt.Op.GtInt);
        int done = c.Op(Flint.Rt.Op.JumpIfFalse).Hole();
        c.Op(Flint.Rt.Op.Self);
        c.Op(Flint.Rt.Op.Local).U8(0).Op(Flint.Rt.Op.Int).I16(1).Op(Flint.Rt.Op.SubInt);
        c.Op(Flint.Rt.Op.TailCall).U8(1);
        c.Patch(done);
        c.Op(Flint.Rt.Op.Local).U8(0).Op(Flint.Rt.Op.Return);
        rt.code = c.Done();
        rt.fns = new[]{ new Flint.Rt.Rt.FnDef(
            new[]{ new Flint.Rt.Rt.Arity(1, false, 2, 0, rt.code.Length) }, 0) };
        long h = rt.MakeClosure(0, System.Array.Empty<long>());
        int before = rt.frames.Count;
        long got3 = rt.Call(h, new[]{ Flint.Rt.Val.Fixnum(200_000) });
        if (Flint.Rt.Val.AsFixnum(got3) != 0)
            throw new System.Exception($"tail recursion gave {Flint.Rt.Val.AsFixnum(got3)}");
        if (rt.frames.Count != before)
            throw new System.Exception($"frames leaked: {before} -> {rt.frames.Count}");
        Console.WriteLine("  ok     ... and 200,000 tail calls in constant frame space");
    }

    /// A tiny assembler, the same shape as the Rust tests'.
    private sealed class Asm {
        private byte[] b = new byte[64];
        private int n;
        public int At() => n;
        private void Put(int x) {
            if (n == b.Length) System.Array.Resize(ref b, n * 2);
            b[n++] = (byte) x;
        }
        public Asm Op(int o) { Put(o); return this; }
        public Asm U8(int v) { Put(v); return this; }
        public Asm U16(int v) { Put(v & 0xFF); Put((v >> 8) & 0xFF); return this; }
        public Asm I16(int v) => U16(v & 0xFFFF);
        public int Hole() { int h = n; Put(0); Put(0); return h; }
        public void Patch(int h) { int o = n - (h + 2); b[h] = (byte)(o & 0xFF); b[h + 1] = (byte)((o >> 8) & 0xFF); }
        public void JumpTo(int target) { Put(Flint.Rt.Op.Jump); int o = target - (n + 2); Put(o & 0xFF); Put((o >> 8) & 0xFF); }
        public byte[] Done() { var outb = new byte[n]; System.Array.Copy(b, outb, n); return outb; }
    }

    /// The collector, under pressure, with the invariant asserted rather than
    /// hoped for. The SAME test as `runtimes/jvm/test/RtFoundation.java`.
    ///
    /// Builds a linked list far larger than the nursery, so it is collected
    /// many times over and every survivor is copied, promoted, and pointed at
    /// from the old generation. Then walks it. A collector that loses ONE
    /// object, or forwards one pointer wrongly, produces a wrong sum -- and the
    /// walk is what turns "it did not crash" into a result.
    private static void GcStress() {
        using var gc = new Flint.Rt.Gc(256 * 1024, 64L * 1024 * 1024);
        var roots = new Flint.Rt.Roots();
        const int N = 200_000;

        // Held only through the shadow stack: the CLR local goes stale at the
        // first collection, which is the whole point of `Push`/`R`.
        int head = roots.Push(Flint.Rt.Val.Nil);
        for (int i = 0; i < N; i++) {
            long cell = gc.Alloc(roots, Flint.Rt.Obj.TyCons, 4);
            if (cell == 0) throw new System.Exception("out of heap at " + i);
            gc.SetSlot(cell, 0, Flint.Rt.Val.Fixnum(i), roots);
            gc.SetSlot(cell, 1, roots.R(head), roots);
            roots.SetR(head, Flint.Rt.Val.Heap(cell));
        }

        long want = (long) N * (N - 1) / 2;
        long sum = 0;
        int seen = 0;
        long cur = roots.R(head);
        while (!Flint.Rt.Val.IsNil(cur)) {
            long a = Flint.Rt.Val.AsHeap(cur);
            sum += Flint.Rt.Val.AsFixnum(Flint.Rt.Obj.Slot(gc.sp, a, 0));
            seen++;
            cur = Flint.Rt.Obj.Slot(gc.sp, a, 1);
        }
        if (seen != N) throw new System.Exception($"walked {seen} of {N}");
        if (sum != want) throw new System.Exception($"sum {sum} want {want}");
        Console.WriteLine($"  ok   {N:N0} objects survive {gc.minors:N0} minor and {gc.majors:N0} major collections intact");

        // Garbage really is reclaimed. Without this the assertion above passes
        // for the wrong reason: a collector that keeps everything loses nothing.
        long before = gc.HeapUsed();
        roots.SetR(head, Flint.Rt.Val.Nil);
        gc.Major(roots);
        if (gc.HeapUsed() >= before)
            throw new System.Exception($"nothing was reclaimed: {before} -> {gc.HeapUsed()}");
        Console.WriteLine($"  ok     ... and dropping them reclaims {before - gc.HeapUsed():N0} bytes");
    }




}
