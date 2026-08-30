namespace Flint.Rt;

/// What every executor in one sandbox SHARES (`doc/decisions/0028`).
///
/// The split is the whole of what makes several host threads on one heap
/// possible. A value stack and a shadow stack are one PER THREAD -- two threads
/// interpreting at once each have their own -- while var slots, constants,
/// singletons and the intern tables are one per SANDBOX, because they are the
/// program rather than the execution.
public sealed class Shared {
    /// EVERY executor in this sandbox, registered once when it is created.
    public readonly List<Roots> all = new();

    /// Every OTHER executor, filled in only BETWEEN `StageStop` and
    /// `ReleaseStop` and empty outside it.
    ///
    /// Transient on purpose: a list that outlived the stop would be roots
    /// belonging to threads that have started running again, and the collector
    /// would walk them while they were being mutated.
    public readonly List<Roots> others = new();

    /// Var slots, read by VAR and written by SET_VAR. A var slot carries no
    /// happens-before for anything else: the heap object it names is published
    /// by the allocation lock and the safepoint.
    public long[] Globals = System.Array.Empty<long>();
    public long[] Consts = System.Array.Empty<long>();
    public long[] Singletons = NewSingletons();

    /// The intern tables. WEAK, and rewritten by the collector at a safepoint.
    public readonly Interns[] interns = Interns.Tables();

    /// The safepoint protocol. One per sandbox, because that is the unit it
    /// stops.
    public readonly Parallel par = new();

    static long[] NewSingletons() {
        long[] s = new long[Rt.SingCount];
        // FILLED WITH NIL, not zero: 0 is the bit pattern of `+0.0`.
        System.Array.Fill(s, Val.Nil);
        return s;
    }
}
