using Flint;

/// Run an image and print what it returned.
///
/// The same shape as the JVM's runner: one source, compiled once, run on every
/// runtime, answers diffed. `doc/decisions/0010` is explicit that the bytecode
/// makes a port cheap and does nothing to make two ports AGREE.
public static class Program {
    public static int Main(string[] args) {
        if (args.Length >= 2 && args[0] == "--threads") return Threads(args[1]);
        if (args.Length >= 2 && args[0] == "--aot") return Aot(args[1]);
        if (args.Length >= 2 && args[0] == "--flags") return Flags(args[1]);
        if (args.Length >= 1 && args[0] == "--rt-foundation") return RtFoundation();
        if (args.Length >= 1 && args[0] == "--rt-snapshot") return RtSnapshot();
        if (args.Length >= 1 && args[0] == "--rt-hash") return RtHash();
        if (args.Length >= 1 && args[0] == "--rt-maps") return RtMaps();
        if (args.Length >= 3 && args[0] == "--rt-shelve") return RtShelve(args[1], args[2]);
        if (args.Length >= 3 && args[0] == "--rt-selfhost") return RtSelfHost(args[1], args[2]);
        if (args.Length >= 2 && args[0] == "--rt-image")
            return RtImage(args[1], args.Length > 2 ? args[2] : null);
        if (args.Length >= 3 && args[0] == "--selfhost") return SelfHost(args[1], args[2]);
        var vm = new Vm(Img.Read(File.ReadAllBytes(args[0])));
        vm.EnsureStarted();
        object outv = vm.RunProgram(new Vm.Closure(vm.Img.Entry, Array.Empty<object>()),
                              new object[] { new Vec() });
        Console.WriteLine(Builtins.Str(outv));
        return 0;
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
            long inner = Flint.Rt.Vec.Nth(rt, v, i);
            sb.Append(Flint.Rt.Val.AsFixnum(Flint.Rt.Vec.Nth(rt, inner, 0)));
            sb.Append('=');
            sb.Append(Flint.Rt.Str.Text(rt, Flint.Rt.Vec.Nth(rt, inner, 1)));
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
        a.roots.Globals = new long[]{ root };
        string before = SnapRender(a, root);
        SnapOk("built a structure to snapshot: " + N + " entries", before.StartsWith("0=item-0;"));

        byte[] verbatim = Flint.Rt.Snap.Capture(a);
        SnapOk("capture produced bytes: " + verbatim.Length, verbatim.Length > 1024);

        var b = new Flint.Rt.Rt(1024 * 1024, 64L * 1024 * 1024);
        b.fingerprint = a.fingerprint;
        b.roots.Globals = new long[1];
        SnapOk("restore accepts it", Flint.Rt.Snap.Restore(b, verbatim));
        SnapOk("the restored heap reads back identically", SnapRender(b, b.roots.Globals[0]) == before);
        SnapOk("and at the SAME address, which is what a memcpy means",
               Flint.Rt.Val.AsHeap(b.roots.Globals[0]) == Flint.Rt.Val.AsHeap(root));

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
        c.roots.Globals = new long[]{ croot };
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
        d.roots.Globals = new long[1];
        SnapOk("importLive accepts it", Flint.Rt.Snap.ImportLive(d, live));
        SnapOk("the rehydrated heap reads back identically", SnapRender(d, d.roots.Globals[0]) == before);
        SnapOk("at a DIFFERENT address, which is what relocating means",
               Flint.Rt.Val.AsHeap(d.roots.Globals[0]) != Flint.Rt.Val.AsHeap(croot));

        // The rehydrated heap has to be a working heap, not just a readable
        // one: keep allocating on it and collect, which is what would trip a
        // bad remembered set or a missed write barrier from pass two.
        long more = SnapBuild(d, 200);
        d.roots.Globals = new long[]{ d.roots.Globals[0], more };
        d.gc.Major(d.roots);
        SnapOk("survives a major collection after import",
               SnapRender(d, d.roots.Globals[0]) == before);
        SnapOk("and the objects allocated after it are intact too",
               SnapRender(d, d.roots.Globals[1]).StartsWith("0=item-0;"));

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
        if (rt.Describe(t) == "an ex-info" && Flint.Rt.Str.IsString(rt, rt.Slot(t, 0)))
            return "the program threw: " + Flint.Rt.Str.Text(rt, rt.Slot(t, 0));
        return "the program threw " + rt.Describe(t);
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
            if (want != null && want != shown) {
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
        HEq("(hash 0)", Flint.Rt.Hash.HashLong(0), 0);
        HEq("(hash 1)", Flint.Rt.Hash.HashLong(1), 1392991556);
        HEq("(hash -1)", Flint.Rt.Hash.HashLong(-1), 1651860712);
        HEq("(hash 42)", Flint.Rt.Hash.HashLong(42), 1871679806);
        HEq("(hash 12345678901234)", Flint.Rt.Hash.HashLong(12345678901234L), -1096982217);
        HEq("(hash Long/MAX_VALUE)", Flint.Rt.Hash.HashLong(long.MaxValue), -2106506049);
        HEq("(hash Long/MIN_VALUE)", Flint.Rt.Hash.HashLong(long.MinValue), 1366273829);
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
            long got = Flint.Rt.Maps.Get(rt, m, Flint.Rt.Val.Fixnum(i), Flint.Rt.Val.Nil);
            if (!Flint.Rt.Val.IsFixnum(got) || Flint.Rt.Val.AsFixnum(got) != i * 10L) return false;
        }
        return true;
    }

    private static int RtMaps() {

    var rt = new Flint.Rt.Rt(4 * 1024 * 1024, 128L * 1024 * 1024);

    // --- the array-map, and the boundary.
    int bas = rt.Mark();
    int m = rt.Push(Flint.Rt.Maps.Empty(rt));
    MOk("an empty map counts 0", Flint.Rt.Maps.Count(rt, rt.R(m)) == 0);
    for (int i = 0; i < 8; i++) rt.SetR(m, Flint.Rt.Maps.Assoc(rt, rt.R(m), K(rt, i), Flint.Rt.Val.Fixnum(i * 10)));
    MOk("8 entries is still a flat array-map", Flint.Rt.Maps.IsArrayMap(rt, rt.R(m)));
    MOk("  and every one reads back", AllPresent(rt, rt.R(m), 0, 8));
    rt.SetR(m, Flint.Rt.Maps.Assoc(rt, rt.R(m), K(rt, 8), Flint.Rt.Val.Fixnum(80)));
    MOk("the 9th promotes to a CHAMP trie", !Flint.Rt.Maps.IsArrayMap(rt, rt.R(m)) && Flint.Rt.Maps.IsMap(rt, rt.R(m)));
    MOk("  and nothing was lost crossing the boundary", AllPresent(rt, rt.R(m), 0, 9));

    // --- a big map, in and out.
    const int N = 2000;
    rt.SetR(m, Flint.Rt.Maps.Empty(rt));
    for (int i = 0; i < N; i++) rt.SetR(m, Flint.Rt.Maps.Assoc(rt, rt.R(m), K(rt, i), Flint.Rt.Val.Fixnum(i * 10)));
    MOk(N + " keys, all present, count agrees", Flint.Rt.Maps.Count(rt, rt.R(m)) == N && AllPresent(rt, rt.R(m), 0, N));
    MOk("a key that was never added is absent",
       Flint.Rt.Maps.Get(rt, rt.R(m), Flint.Rt.Val.Fixnum(-1), Flint.Rt.Val.Nil) == Flint.Rt.Val.Nil);
    // Re-assoc with the same value must not grow the map.
    rt.SetR(m, Flint.Rt.Maps.Assoc(rt, rt.R(m), K(rt, 5), Flint.Rt.Val.Fixnum(50)));
    MOk("re-assoc with an identical value does not grow it", Flint.Rt.Maps.Count(rt, rt.R(m)) == N);

    // --- CANONICAL FORM: the property CHAMP is chosen for.
    int viaDelete = rt.Push(rt.R(m));
    for (int i = 0; i < N; i += 2) {
      rt.SetR(viaDelete, Flint.Rt.Maps.Dissoc(rt, rt.R(viaDelete), K(rt, i)));
    }
    int direct = rt.Push(Flint.Rt.Maps.Empty(rt));
    for (int i = 1; i < N; i += 2) {
      rt.SetR(direct, Flint.Rt.Maps.Assoc(rt, rt.R(direct), K(rt, i), Flint.Rt.Val.Fixnum(i * 10)));
    }
    MOk("built-by-deleting and built-directly have the same count",
       Flint.Rt.Maps.Count(rt, rt.R(viaDelete)) == Flint.Rt.Maps.Count(rt, rt.R(direct)));
    MOk("  ... and are =", Flint.Rt.Maps.Eq(rt, rt.R(viaDelete), rt.R(direct)));
    MOk("  ... and HASH ALIKE, which is what canonical form means",
       Flint.Rt.Eq.HashValue(rt, rt.R(viaDelete)) == Flint.Rt.Eq.HashValue(rt, rt.R(direct)));
    MOk("  ... and the deleted keys really are gone",
       Flint.Rt.Maps.Get(rt, rt.R(viaDelete), K(rt, 0), Flint.Rt.Val.Nil) == Flint.Rt.Val.Nil
       && Flint.Rt.Maps.Get(rt, rt.R(viaDelete), K(rt, 2), Flint.Rt.Val.Nil) == Flint.Rt.Val.Nil);

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
      for (int i = 0; i < 20; i++) rt.SetR(c, Flint.Rt.Maps.Assoc(rt, rt.R(c), Flint.Rt.Val.Fixnum(1000000 + i), Flint.Rt.Val.Fixnum(i)));
      int ka = rt.Push(Flint.Rt.Str.Of(rt, "Aa"));
      int kb = rt.Push(Flint.Rt.Str.Of(rt, "BB"));
      rt.SetR(c, Flint.Rt.Maps.Assoc(rt, rt.R(c), rt.R(ka), Flint.Rt.Val.Fixnum(111)));
      rt.SetR(c, Flint.Rt.Maps.Assoc(rt, rt.R(c), rt.R(kb), Flint.Rt.Val.Fixnum(222)));
      MOk("both colliding keys are stored and distinct",
         Flint.Rt.Val.AsFixnum(Flint.Rt.Maps.Get(rt, rt.R(c), rt.R(ka), Flint.Rt.Val.Nil)) == 111
         && Flint.Rt.Val.AsFixnum(Flint.Rt.Maps.Get(rt, rt.R(c), rt.R(kb), Flint.Rt.Val.Nil)) == 222);
      MOk("  and the count counts them both", Flint.Rt.Maps.Count(rt, rt.R(c)) == 22);
      rt.SetR(c, Flint.Rt.Maps.Dissoc(rt, rt.R(c), rt.R(ka)));
      MOk("  removing one leaves the other",
         Flint.Rt.Maps.Get(rt, rt.R(c), rt.R(ka), Flint.Rt.Val.Nil) == Flint.Rt.Val.Nil
         && Flint.Rt.Val.AsFixnum(Flint.Rt.Maps.Get(rt, rt.R(c), rt.R(kb), Flint.Rt.Val.Nil)) == 222);
      MOk("  and the collision node collapsed back to an inline entry",
         Flint.Rt.Maps.Count(rt, rt.R(c)) == 21);
    }

    // --- keys that are not fixnums, and a collection under collection.
    int s = rt.Push(Flint.Rt.Maps.Empty(rt));
    rt.SetR(s, Flint.Rt.Maps.Assoc(rt, rt.R(s), Flint.Rt.Str.Of(rt, "hello, world"), Flint.Rt.Val.Fixnum(1)));
    rt.SetR(s, Flint.Rt.Maps.Assoc(rt, rt.R(s), Flint.Rt.Str.Keyword(rt, null, "kw"), Flint.Rt.Val.Fixnum(2)));
    int vk = rt.Push(Flint.Rt.Vec.Empty(rt));
    rt.SetR(vk, Flint.Rt.Vec.Conj(rt, rt.R(vk), Flint.Rt.Val.Fixnum(7)));
    rt.SetR(s, Flint.Rt.Maps.Assoc(rt, rt.R(s), rt.R(vk), Flint.Rt.Val.Fixnum(3)));
    // A SEPARATE but equal key must find the same entry -- that is the whole
    // difference between `=` and identity, and where interning would be a
    // shortcut rather than the answer.
    int vk2 = rt.Push(Flint.Rt.Vec.Empty(rt));
    rt.SetR(vk2, Flint.Rt.Vec.Conj(rt, rt.R(vk2), Flint.Rt.Val.Fixnum(7)));
    MOk("a string key reads back",
       Flint.Rt.Val.AsFixnum(Flint.Rt.Maps.Get(rt, rt.R(s), Flint.Rt.Str.Of(rt, "hello, world"), Flint.Rt.Val.Nil)) == 1);
    MOk("an equal-but-separate VECTOR key finds the same entry",
       Flint.Rt.Val.AsFixnum(Flint.Rt.Maps.Get(rt, rt.R(s), rt.R(vk2), Flint.Rt.Val.Nil)) == 3);

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
        long compiler = rt.roots.Globals[slot];
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

    /// What the compiler decided, and whether this runtime acted on it.
    ///
    /// `:optimize [perf]` has to mean the same thing on all three runtimes and
    /// cannot be carried the same way on any two: on wasm it changes the
    /// artifact, here the IL is emitted at load time from the same bytecode. So
    /// the image carries the decision and the Vm reads it -- and this prints
    /// both halves so the claim is checked rather than asserted in a comment.
    private static int Flags(string path) {
        var img = Img.Read(File.ReadAllBytes(path));
        var vm = new Vm(img);
        Console.WriteLine($"flags={img.Flags} aot={(vm.AotEnabled ? "true" : "false")}");
        return 0;
    }

    /// Run the flint COMPILER on .NET, and compare what it emits with what the
    /// native compiler emits from the same input -- byte for byte.
    ///
    /// This is the difference between "runs programs" and "self-hosts". The
    /// compiler is the largest flint program there is, so it reaches builtins a
    /// small program never does; and an image that DIFFERS is a compiler that
    /// differs, whose difference will surface in a program no test here runs.
    private static int SelfHost(string specPath, string refPath) {
        var vm = new Vm(Img.Read(File.ReadAllBytes("dist/flintc.bytecode")));
        vm.EnsureStarted();

        // `flint.selfhost/main` is a VAR, not a named entry in the function
        // table: the initialisers put its closure in a slot, which is why
        // EnsureStarted has to have run.
        int slot = -1;
        for (int i = 0; i < vm.Img.VarNames.Length; i++) {
            if (vm.Img.VarNames[i] == "flint.selfhost/main") slot = i;
        }
        if (slot < 0) { Console.WriteLine("  FAIL flint.selfhost/main is not in the var table"); return 1; }
        object compiler = vm.GetVarPublic(slot);
        if (compiler == null) { Console.WriteLine("  FAIL flint.selfhost/main is unbound"); return 1; }

        // ONE argument: anything that is not "wasm" or "project" is the SPEC.
        object outv = vm.Call(compiler, new object[] { new Vec(new object[] { File.ReadAllText(specPath) }) });
        string first = Builtins.Str(outv).Split('\n')[0].Trim();
        if (first.Length < 100) {
            Console.WriteLine("  FAIL the compiler did not emit an image: "
                              + first.Substring(0, Math.Min(200, first.Length)));
            return 1;
        }
        Console.WriteLine($"  ok   the flint compiler ran on the CLR and emitted {first.Length} base64 chars");

        byte[] got = Convert.FromBase64String(first);
        byte[] want = Convert.FromBase64String(File.ReadAllText(refPath).Split('\n')[0].Trim());
        if (got.Length == want.Length && got.AsSpan().SequenceEqual(want)) {
            Console.WriteLine($"  ok   byte for byte the image the native compiler emits ({got.Length} bytes)");
            return 0;
        }
        int at = -1;
        for (int i = 0; i < Math.Min(got.Length, want.Length); i++) if (got[i] != want[i]) { at = i; break; }
        Console.WriteLine($"  FAIL the image differs from the native compiler's: {got.Length} bytes"
                          + $" against {want.Length}"
                          + (at < 0 ? ", one a prefix of the other" : $", first differing byte at {at}"));
        return 1;
    }

    /// AOT: the same program interpreted and compiled, and the answers diffed.
    ///
    /// The only thing worth asserting about a compiler is that it did not
    /// change the answer. A backend that is fast and wrong is worse than no
    /// backend, and "it ran" does not tell them apart.
    private static int Aot(string path) {
        var img = Img.Read(File.ReadAllBytes(path));

        var interp = new Vm(img);
        interp.EnsureStarted();
        object want = interp.RunProgram(new Vm.Closure(img.Entry, Array.Empty<object>()),
                                  new object[] { new Vec() });

        var jit = new Vm(img) { AotEnabled = true };
        jit.EnsureStarted();
        object got = jit.RunProgram(new Vm.Closure(img.Entry, Array.Empty<object>()),
                              new object[] { new Vec() });

        bool same = Builtins.Eq(want, got);
        Console.WriteLine("  " + (same ? "ok  " : "FAIL")
            + " compiled and interpreted agree (" + jit.CompiledCount + " arities compiled"
            + (System.Environment.GetEnvironmentVariable("FLINT_AOT_HISTO") == "1"
               ? ", " + Flint.Aot.Fused + " compares fused, " + Flint.Aot.Runs + " int runs" : "")
            + ")");
        if (System.Environment.GetEnvironmentVariable("FLINT_AOT_HISTO") == "1") {
            var h = new System.Collections.Generic.List<System.Collections.Generic.KeyValuePair<int,int>>(Flint.Aot.Histo);
            h.Sort((a, b) => b.Value.CompareTo(a.Value));
            foreach (var e in h) System.Console.WriteLine($"       0x{e.Key:X2} {e.Value}");
        }
        if (!same) {
            Console.WriteLine("        interpreted " + Builtins.Str(want));
            Console.WriteLine("        compiled    " + Builtins.Str(got));
            return 1;
        }
        if (jit.CompiledCount == 0) {
            if (!jit.CanCompile) {
                // Legitimate, not a gap: green threads mean a compiled arity's
                // continuation would be the CLR stack. The answers still had to
                // agree, and did.
                Console.WriteLine("  ok   nothing compiled: green threads, so it interprets");
                return 0;
            }
            Console.WriteLine("  FAIL nothing was compiled, so nothing was tested");
            return 1;
        }

        // What it is FOR. Best-of, because a single timing on a JIT host is
        // mostly warm-up.
        double Best(Vm vm, int runs) {
            double best = double.MaxValue;
            for (int i = 0; i < runs; i++) {
                var sw = System.Diagnostics.Stopwatch.StartNew();
                vm.RunProgram(new Vm.Closure(img.Entry, Array.Empty<object>()), new object[] { new Vec() });
                sw.Stop();
                best = Math.Min(best, sw.Elapsed.TotalMilliseconds);
            }
            return best;
        }
        double ti = Best(interp, 7), tc = Best(jit, 7);
        Console.WriteLine($"       interpreted {ti:F2} ms, compiled {tc:F2} ms, {ti / tc:F2}x");
        return 0;
    }

    /// Several threads running ONE flint program.
    ///
    /// There is no safepoint here and no collector of ours to stop, because the
    /// CLR's owns lifetime. What has to be right is flint's own shared state --
    /// the var slots and atoms.
    private static int Threads(string path) {
        int failures = 0;
        void Ok(bool c, string what) {
            Console.WriteLine("  " + (c ? "ok  " : "FAIL") + " " + what);
            if (!c) failures++;
        }

        var img = Img.Read(File.ReadAllBytes(path));
        var vm = new Vm(img);
        vm.EnsureStarted();

        int fnTally = -1, fnWork = -1;
        for (int i = 0; i < img.Fns.Length; i++) {
            string n = img.Fns[i].Name;
            if (n == null) continue;
            if (n.EndsWith("tally")) fnTally = i;
            if (n.EndsWith("work")) fnWork = i;
        }
        // Both must be REACHABLE from the entry, or tree shaking removes them
        // and there is nothing to call (`doc/decisions/0002`).
        if (fnTally < 0 || fnWork < 0) {
            Console.WriteLine($"  FAIL the program's functions are findable (tally={fnTally} work={fnWork})");
            return 1;
        }
        Ok(true, "the program's functions are findable");

        const int Threads = 8, Per = 200;
        object want = null;
        bool sameEvery = true;
        Parallel.For(0, Threads, _ => {
            object got = vm.Call(new Vm.Closure(fnWork, Array.Empty<object>()), new object[] { 100L });
            lock (img) {
                if (want == null) want = got;
                else if (!Builtins.Eq(want, got)) sameEvery = false;
            }
        });
        Ok(sameEvery, $"eight threads computing the same thing agree ({Builtins.Str(want)})");

        // `swap!` is a CAS retry loop in `lib/clojure/core.cljc`, so these
        // increments must all land. A lost update shows up as a smaller number
        // and nothing else, which is why this counts rather than samples.
        Parallel.For(0, Threads, _ => {
            for (int i = 0; i < Per; i++)
                vm.Call(new Vm.Closure(fnTally, Array.Empty<object>()), Array.Empty<object>());
        });
        long got2 = (long) vm.Call(new Vm.Closure(fnTally, Array.Empty<object>()), Array.Empty<object>());
        long want2 = (long) Threads * Per + 1;
        Ok(got2 == want2,
           $"{Threads} threads x {Per} increments lose none (got {got2}, want {want2})");

        Console.WriteLine(failures == 0 ? "\nall checks passed" : "\nFAILED");
        return failures == 0 ? 0 : 1;
    }
}
