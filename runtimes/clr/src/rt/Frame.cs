namespace Flint.Rt;

/// One flint call, ported from `runtime/src/vm.rs`.
///
/// Deliberately does NOT cache the closure. It used to, in the Rust, and that
/// copy was a root the collector could not see: after a collection moved the
/// closure, `Upval` and `Self` read a stale address. `Stack[RetTo]` IS this
/// frame's closure until it returns.
public sealed class Frame {
    public int Fp, Ip, End, RetTo, Handlers;
}
