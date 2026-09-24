namespace Flint.Rt;

using System;
using System.IO;
using System.Reflection;
using System.Text;

/// THE FOUR OPERATIONS, on the CLR. A SPIKE -- see `doc/goals/clr-target.md`
/// for what is and is not done.
///
/// ## How the image reaches this class
///
/// TWO DOORS, AND `Boot(bridge, image)` IS THE REAL ONE. The artifact `:to :clr`
/// produces is an assembly emitted from scratch by `flint.clr`, carrying the
/// bytecode image as a static byte array in `.text` -- a `FieldRva` field, the
/// same thing a C# `ReadOnlySpan<byte>` literal compiles to. The generated code
/// reads that array and hands it here. Nothing in this class needs to know
/// where the bytes came from, which is the point: the image is data the caller
/// supplies, not a file this class goes looking for.
///
/// `Boot(bridge)` -- the no-image overload -- reads a payload appended after the
/// last PE section instead. That was the SPIKE's route and it works (a PE loader
/// finds everything through the section table, so bytes past the last section
/// are off the map, and the whole target was a byte concatenation). It is kept
/// because it runs today and is what the spike's tests drive, but it is
/// scaffolding, not the design: it cannot rename the assembly, and it requires a
/// file on disk, so an assembly loaded from a `byte[]` or bundled by
/// single-file publish has no `Location` and finds nothing.
///
/// Trailer layout, for as long as that door exists:
///
///     [ assembly bytes ][ image bytes ][ meta bytes ]
///     u32 imageLen
///     u32 metaLen
///     u32 format        ; 1
///     "FLINTCLR"        ; magic LAST, so the reader seeks to (end - 20)
///
/// The magic is last on purpose. A reader has one fixed offset to try -- the
/// end -- and lengths that are only believed once the magic is there.
public static class Artifact {

    /// 8 magic + three u32.
    public const int TrailerSize = 20;
    public const int TrailerFormat = 1;
    static readonly byte[] Magic = Encoding.ASCII.GetBytes("FLINTCLR");

    // --- the host's end of the system bridge -------------------------------
    //
    // ONE OBJECT, NOT TWELVE EXPORTS. The wasm face of this is
    // `flint_install_port`, `flint_deliver`, `flint_drain`, `flint_resume`,
    // `flint_continue`, `flint_close`, `flint_port_state`, `flint_in_alloc`,
    // `flint_grant`, `flint_answer`, `flint_system_port`, `flint_events_ptr`,
    // plus `out_ptr`/`out_len` -- fourteen, most of which exist only because
    // wasm cannot pass a byte array or a callback across the boundary. The CLR
    // can pass both, so the same protocol is one interface with five methods,
    // and the host holds a real object rather than minting integer ids into
    // linear memory.
    //
    // The direction of every method is from the ARTIFACT'S point of view: the
    // artifact calls into the host. The host's own direction -- writing to the
    // bridge -- needs no operation here, because the host owns the object it
    // passed to `Boot` and writes into it directly, then calls `Loop`.

    /// One message on one port.
    public readonly record struct Msg(long Port, byte[] Bytes);

    public interface IBridge {
        /// Host -> guest. The artifact drains this until it is empty at the top
        /// of every `Loop`. `false` means nothing is waiting.
        ///
        /// A QUEUE THE HOST OWNS, which is the whole reason there is no
        /// `deliver` operation: back-pressure is the host holding the message
        /// and offering it again on the next `Loop`, and `TryTake` returning
        /// `false` is indistinguishable from a host that chose to wait.
        bool TryTake(out Msg m);

        /// Guest -> host. Wire-encoded, always; the host decodes.
        void Put(Msg m);

        /// The guest asked to open a named port. Return the host port id that
        /// serves it, or -1 to refuse. `request` is the wire-encoded argument.
        ///
        /// NOT A BOOLEAN. "Yes" is not expressible without naming a port,
        /// because until the host says which port there is none
        /// (`DECISIONS.md#ports-are-the-hosts`).
        long Open(long token, byte[] request);

        /// The guest asked the host a question whose answer is a value.
        /// Wire-encoded answer, or null to refuse.
        byte[] Answer(long token, byte[] request);

        /// A port the host held has been closed from the guest's side.
        void Closed(long port);
    }

    /// What `Loop` answers. THESE NUMBERS ARE THE ABI -- they are
    /// `flint_resume`'s and `rt.status`'s, unchanged -- but a host on .NET
    /// should not have to know that, so they are named.
    public enum Status {
        /// Nothing left to run. The program is over.
        Done = 0,
        /// It threw. `Thrown` renders it.
        Threw = 1,
        /// Parked on the host. THE RESTING STATE, not an error and not
        /// progress remaining: the control plane is a green thread parked on
        /// the system port, so a live sandwith with nothing to do reports this.
        /// The host writes to the bridge and calls `Loop` again.
        NeedsHost = 2,
    }

    /// The host port id of the system bridge. The host picks ids for every
    /// other port; this one is fixed so that `Boot` needs no argument for it.
    public const long SystemPort = 1;

    static Rt rt;
    static IBridge bridge;
    static Img.Loaded loaded;
    static byte[] metaBytes = Array.Empty<byte>();
    static bool booted;
    static string[] unresolved = Array.Empty<string>();
    static int importedNatives;

    /// The live runtime, for a host that wants more than these four operations.
    /// Null before `Boot`.
    public static Rt Runtime => rt;

    // --- boot ---------------------------------------------------------------

    /// Instantiate this artifact's image and give it `bridge` as its system
    /// port, reading the image from an appended trailer. The spike's door --
    /// see the class comment; prefer the overload.
    public static void Boot(IBridge bridge) => Boot(bridge, null);

    /// Instantiate `image` and give it `bridge` as its system port. THE REAL
    /// DOOR: a generated artifact reads its own `FieldRva` byte array and calls
    /// this, so the bytes are the caller's to supply and this class never goes
    /// looking for a file.
    public static void Boot(IBridge bridge, byte[] image) {
        if (booted) throw new InvalidOperationException("flint: this artifact is already booted");
        if (bridge == null) throw new ArgumentNullException(nameof(bridge));

        if (image == null) {
            var t = ReadTrailer();
            if (t == null) {
                throw new InvalidOperationException(
                    "flint: this assembly carries no image. Either it was not built by the\n" +
                    "`:to :clr` target, or it was loaded from memory and so cannot read its\n" +
                    "own file -- pass the image to `Boot(bridge, image)` in that case.");
            }
            image = t.Image;
            metaBytes = t.Meta;
        }

        // The sizes the conformance harness uses for the host-port tests
        // (`conform/Program.cs:703`). Not tuned, and a host cannot yet say --
        // see the report's list of what is missing.
        rt = new Rt(4L * 1024 * 1024, 512L * 1024 * 1024);
        loaded = Img.Load(rt, image);
        if (loaded == null) {
            throw new InvalidOperationException(
                "flint: the embedded bytes are not a flint image, or are a version this " +
                "runtime does not read (this runtime reads version " + Img.Version + ")");
        }

        // WHICH NATIVES DID NOT RESOLVE -- recorded, NOT refused. `Img.Load`
        // leaves a missing builtin null on purpose, and the reason is in its own
        // comment: an image imports every builtin its NAMESPACES mention, so a
        // trivial program imports 88 of them by reaching `clojure.core`, and
        // almost none are called. Refusing at load would reject programs that
        // run perfectly.
        //
        // But a host that supplies the natives itself cannot otherwise SEE that
        // it missed one until a program reaches it, so the list is kept and
        // `Prop("natives-unresolved")` hands it over. A fact to query, not a
        // verdict at the door.
        var missing = new System.Collections.Generic.List<string>();
        for (int i = 0; i < loaded.nativeNames.Length; i++) {
            if (rt.natives[i] == null) missing.Add(loaded.nativeNames[i]);
        }
        missing.Sort();
        unresolved = missing.ToArray();
        importedNatives = loaded.nativeNames.Length;

        Artifact.bridge = bridge;
        // BEFORE ANYTHING RUNS. `InstallSystemPort` is what makes
        // `BootSystemThreadOnce` spawn the control plane, and a sandbox given
        // its door late has already decided it has none.
        Conc.InstallSystemPort(rt, SystemPort, Str.Of(rt, "system"));
        rt.EnsureStarted();
        booted = true;
    }

    // --- loop ---------------------------------------------------------------

    /// Pump: give the guest everything the host wrote, run until there is no
    /// more work, hand back everything the guest produced.
    ///
    /// DRAIN, THEN RUN, THEN DRAIN. The trailing drain is not tidiness: the
    /// scheduler reports `NeedsHost` when it has undrained events, so a pump
    /// that ran without draining afterwards would report work remaining that
    /// it was itself holding.
    public static Status Loop() {
        Require();
        while (bridge.TryTake(out var m)) {
            // FALSE IS NOT AN ERROR -- it is the guest's buffer being full. The
            // message goes back to the host, which will offer it again.
            if (!Conc.HostDeliver(rt, m.Port, m.Bytes)) { Offer(m); break; }
        }

        long guard = 0;
        for (;;) {
            Conc.Drive(rt);
            if (!Pass()) break;
            // A pump that makes no progress is a bug in the host's replies, not
            // a reason to spin. The number is the one every other flint host
            // uses (`sdks/esm/src/guest.js:69`).
            if (++guard > 1_000_000) {
                throw new InvalidOperationException("flint: the host pump made no progress");
            }
            if (Now() != Status.NeedsHost) break;
        }
        Pass();
        return Now();
    }

    /// Drain one round of events to the host. Returns whether anything moved,
    /// which is what tells `Loop` whether another drive can do more.
    static bool Pass() {
        var evs = Conc.DrainEvents(rt);
        if (evs.Count == 0) return false;
        for (int i = 0; i < evs.Count; i++) {
            int at = i * 20;
            long kind = Word(evs.Bytes, at);
            long a = Word(evs.Bytes, at + 4);
            long off = Word(evs.Bytes, at + 12);
            long len = Word(evs.Bytes, at + 16);
            byte[] payload = new byte[len];
            Array.Copy(evs.Bytes, (int) off, payload, 0, (int) len);

            if (kind == Conc.EV_MESSAGE) {
                bridge.Put(new Msg(a, payload));
            } else if (kind == Conc.EV_OPEN) {
                long port = bridge.Open(a, payload);
                if (port < 0) Conc.HostContinue(rt, a, false);
                else Conc.HostGrant(rt, a, port);
            } else if (kind == Conc.EV_REQUEST) {
                byte[] answer = bridge.Answer(a, payload);
                if (answer == null) Conc.HostContinue(rt, a, false);
                else Conc.HostAnswer(rt, a, answer);
            } else if (kind == Conc.EV_CLOSED) {
                bridge.Closed(a);
                Conc.HostClosePort(rt, a);
            }
            // EV_RETAIN and EV_RELEASE are the collector's business and need no
            // host reply; they are deliberately not surfaced.
        }
        return true;
    }

    static Status Now() {
        int s = rt.status != 0 ? rt.status : (Val.IsNil(rt.thrown) ? 0 : 1);
        return (Status) s;
    }

    /// A message the guest would not take, handed back. The host offered it, so
    /// the host is where it belongs until the next `Loop`.
    static void Offer(Msg m) => pushback = m;
    static Msg? pushback;

    /// The pushed-back message, if the last `Loop` hit back-pressure. A host
    /// that re-offers from its own queue can ignore this.
    public static Msg? Pending => pushback;

    static long Word(byte[] b, int at) {
        long v = 0;
        for (int i = 3; i >= 0; i--) v = (v << 8) | (b[at + i] & 0xffL);
        return v;
    }

    // --- link ---------------------------------------------------------------

    /// Wire a runtime hook in by name.
    ///
    /// The hook's shape is WIRE BYTES IN, WIRE BYTES OUT, and that is the whole
    /// reason it is usable: a host that had to supply a `Builtins.Fn` would be
    /// handing over a function that reads NaN-boxed values off the interpreter's
    /// value stack, which is not a contract to put in front of anyone. Bytes are
    /// the encoding the system port already uses, so a host that can talk to the
    /// bridge can already write a hook.
    ///
    /// BEFORE `Boot`, ALWAYS. `Img.Load` resolves natives by name once, so a
    /// hook registered afterwards is never reached. This refuses rather than
    /// silently doing nothing.
    public static void Link(string name, Func<byte[], byte[]> fn) {
        if (booted) {
            throw new InvalidOperationException(
                "flint: `Link` must be called before `Boot`. An image resolves its natives by\n" +
                "name once, while it loads, so a hook wired in afterwards is never reached.");
        }
        if (name == null) throw new ArgumentNullException(nameof(name));
        if (fn == null) throw new ArgumentNullException(nameof(fn));
        Builtins.Register(name, (r, at, argc) => {
            // ONE ARGUMENT. A spike limit, not a design: encoding N arguments
            // means building a vector on the guest heap first, and the rooting
            // that needs is real work this does not do yet.
            byte[] said = Codec.Encode(r, argc > 0 ? r.VAt(at) : Val.Nil);
            byte[] got = fn(said);
            return got == null ? Val.Nil : Codec.Decode(r, got);
        });
    }

    /// The raw form, for a host that does reference this assembly and wants the
    /// interpreter's own calling convention. Same ordering rule.
    public static void LinkRaw(string name, Builtins.Fn fn) {
        if (booted) throw new InvalidOperationException("flint: `LinkRaw` must be called before `Boot`");
        Builtins.Register(name, fn);
    }

    // --- prop ---------------------------------------------------------------

    /// Read a metadata property of this artifact into `buf`. Returns the number
    /// of bytes the value needs -- so a caller passing a null or short buffer is
    /// told the size rather than failing -- or -1 if there is no such property.
    ///
    /// NO EDN PARSER. The metadata travels as `pr-str` of the map
    /// `src/flint/modmeta.cljc` builds, and this scans it for the one key it was
    /// asked for, exactly as `host/run.mjs:parseFeatures` does and for the same
    /// reason: a runner needs three or four facts and pulling a reader in to get
    /// them is the wrong trade. `prop("meta", buf)` hands over the whole map for
    /// a caller that does want to parse it.
    ///
    /// READABLE WITHOUT BOOTING. This does not touch `rt` and does not need
    /// `Boot` -- which is the point of `module-metadata-and-shards`: a runner
    /// decides WHETHER to instantiate, and that decision cannot depend on
    /// having already done it.
    ///
    /// IT REPORTS FACTS AND DECIDES NOTHING. There is deliberately no
    /// `Compatible(...)` here and no comparison of any kind. WHICH SEMVER
    /// RELATION COUNTS AS COMPATIBLE IS NOT SETTLED -- flint is pre-1.0, where
    /// the usual "same major" rule says every release breaks everything, and
    /// `module-metadata-and-shards` deliberately carries BOTH a version and a
    /// hash precisely because neither alone is the answer. Baking a rule in here
    /// would take that decision on the caller's behalf and hide it in a runtime.
    /// So `version` and `compat-key` are exposed and the caller decides.
    public static int Prop(string name, byte[] buf) {
        byte[] meta = metaBytes.Length > 0 ? metaBytes : (ReadTrailer()?.Meta ?? Array.Empty<byte>());
        byte[] v;
        switch (name) {
            case "meta": v = meta; break;
            case "version": v = Scan(meta, ":version"); break;
            case "abi": v = Scan(meta, ":abi"); break;
            case "compat-key": v = Scan(meta, ":key"); break;
            case "runtime": v = Encoding.UTF8.GetBytes("clr"); break;
            case "image-version": v = Encoding.UTF8.GetBytes(Img.Version.ToString()); break;
            // THESE TWO NEED `Boot`, and the rest do not. The asymmetry is real
            // and worth stating rather than smoothing over: the others describe
            // the artifact, which is inert and knowable; these describe what
            // linking that artifact against THIS host actually produced, and
            // there is no answer before it happens.
            case "natives":
                v = Encoding.UTF8.GetBytes(booted
                    ? (importedNatives - unresolved.Length) + "/" + importedNatives + " resolved"
                    : "unknown until boot");
                break;
            case "natives-unresolved":
                v = Encoding.UTF8.GetBytes(string.Join(",", unresolved));
                break;
            // The image's FNV-1a, as `Img.Load` computed it. Also needs `Boot`.
            //
            // A CROSS-CHECK, not a feature. `flint.clr` emits IL that computes
            // this same hash over the same `FieldRva` bytes, so three
            // independent implementations -- the compiler's cljc, the CIL it
            // emitted, and this C# -- have to agree on one number. A
            // `FieldRva` array at the wrong offset returns a plausible wrong
            // hash rather than failing, and this is what makes that visible.
            case "fingerprint":
                v = Encoding.UTF8.GetBytes(booted ? rt.fingerprint.ToString()
                                                  : "unknown until boot");
                break;
            default: return -1;
        }
        if (v == null) return -1;
        if (buf != null && buf.Length >= v.Length) Array.Copy(v, buf, v.Length);
        return v.Length;
    }

    /// The string value following `key` in a `pr-str`'d map. Narrow on purpose,
    /// and narrow in the way its one precedent is: only a quoted string value is
    /// found, so `:compat` (a map) is not, and `prop("meta", ...)` is the answer
    /// for anything structured.
    static byte[] Scan(byte[] meta, string key) {
        string s = Encoding.UTF8.GetString(meta);
        int i = s.IndexOf(key + " ", StringComparison.Ordinal);
        if (i < 0) return null;
        int j = i + key.Length + 1;
        if (j >= s.Length) return null;
        if (s[j] == '"') {
            int e = s.IndexOf('"', j + 1);
            if (e < 0) return null;
            return Encoding.UTF8.GetBytes(s.Substring(j + 1, e - j - 1));
        }
        int k = j;
        while (k < s.Length && s[k] != ' ' && s[k] != ',' && s[k] != '}' && s[k] != ']') k++;
        return Encoding.UTF8.GetBytes(s.Substring(j, k - j));
    }

    // --- the trailer --------------------------------------------------------

    public sealed class Payload {
        public byte[] Image;
        public byte[] Meta;
    }

    /// Read this assembly's own appended payload, or null if there is none.
    public static Payload ReadTrailer() {
        string path;
        try { path = typeof(Artifact).Assembly.Location; }
        catch (Exception) { return null; }
        if (string.IsNullOrEmpty(path) || !File.Exists(path)) return null;
        try {
            using var fs = File.OpenRead(path);
            if (fs.Length < TrailerSize) return null;
            fs.Seek(-TrailerSize, SeekOrigin.End);
            byte[] t = new byte[TrailerSize];
            fs.ReadExactly(t);
            for (int i = 0; i < 8; i++) if (t[12 + i] != Magic[i]) return null;
            long imageLen = Word(t, 0), metaLen = Word(t, 4), format = Word(t, 8);
            if (format != TrailerFormat) return null;
            long total = imageLen + metaLen + TrailerSize;
            if (total > fs.Length) return null;
            fs.Seek(-total, SeekOrigin.End);
            var p = new Payload { Image = new byte[imageLen], Meta = new byte[metaLen] };
            fs.ReadExactly(p.Image);
            fs.ReadExactly(p.Meta);
            return p;
        } catch (IOException) { return null; }
    }

    static void Require() {
        if (!booted) throw new InvalidOperationException("flint: `Boot` first");
    }
}
