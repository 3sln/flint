//! Does a live snapshot mean the same thing on every runtime?
//!
//! `0015` says the snapshot is "a serialised internal layout, not an
//! interchange format", and that a mismatch must be refused loudly rather than
//! read as "a plausible-looking heap that means something else". The stamp it
//! refuses on is `MAGIC` and `VERSION` -- and those are IDENTICAL on all three
//! runtimes, so a snapshot from one is accepted by another with no complaint
//! at all. Whether that is a live hazard or an accidental capability depends
//! on a fact nobody had measured: are the bytes the same?
//!
//! `RtSnapshot` and `--rt-snapshot` compare the two PORTS against each other,
//! byte counts included. Native has never been in that comparison. This builds
//! the same structure `RtSnapshot.build` does, with the same operations in the
//! same order, and writes the live snapshot where the gate can `cmp` it.
//!
//!     cargo run -p flint-rt --features diagnostics --example livedump -- <out>
use flint_rt::rt::Rt;
use flint_rt::value::Value;

/// The same shape as the ports build: a vector of vectors, each holding a
/// fixnum and a string, so interior references, a non-`Vals` layout and the
/// index all have to survive.
fn build(rt: &mut Rt, n: i64) -> Value {
    let base = rt.mark();
    let empty = rt.empty_vec();
    let vec = rt.push(empty);
    for i in 0..n {
        let empty = rt.empty_vec();
        let inner = rt.push(empty);
        let f = Value::fixnum(i);
        let v = rt.r(inner);
        let joined = rt.conj(v, f);
        rt.set_r(inner, joined);
        // ROOTED BEFORE THE READ, the same way the ports have to do it: the
        // string allocates, and an allocation can move the vector that was
        // already read out from under the value (`0031`).
        let s = rt.string(&alloc_name(i));
        let si = rt.push(s);
        let v = rt.r(inner);
        let sv = rt.r(si);
        let joined = rt.conj(v, sv);
        rt.set_r(inner, joined);
        rt.pop_to(si);
        let outer = rt.r(vec);
        let innerv = rt.r(inner);
        let joined = rt.conj(outer, innerv);
        rt.set_r(vec, joined);
        rt.pop_to(inner);
    }
    let out = rt.r(vec);
    rt.pop_to(base);
    out
}

fn alloc_name(i: i64) -> String {
    format!("item-{i}")
}

/// Read the structure back, the way the ports' `render` does, so one string
/// covers every element, its type and its order.
fn render(rt: &mut Rt, v: Value) -> String {
    let mut out = String::new();
    let n = rt.vec_count(v);
    for i in 0..n {
        let inner = rt.vec_nth(v, i, flint_rt::value::NIL);
        let f = rt.vec_nth(inner, 0, flint_rt::value::NIL);
        let s = rt.vec_nth(inner, 1, flint_rt::value::NIL);
        let mut buf = flint_rt::rt::sbuf();
        let txt: String = rt.as_str(s, &mut buf).unwrap_or("").into();
        out.push_str(&format!("{}={};", rt.as_i64(f).unwrap_or(-1), txt));
    }
    out
}

fn main() {
    // THE CROSSING, when asked: read a live snapshot written by ANOTHER
    // runtime and see what happens. `0015` says the format is "a serialised
    // internal layout, not an interchange format" and that a mismatch must be
    // refused loudly -- but the stamp it refuses on is `MAGIC` and `VERSION`,
    // and those are identical on all three. So this is the question nobody had
    // asked: is a port's snapshot refused, read correctly, or read wrongly?
    if let Ok(src) = std::env::var("LIVEDUMP_IMPORT") {
        let bytes = std::fs::read(&src).expect("read");
        let mut rt = Rt::with_heap(3 * 1024 * 1024, 64 * 1024 * 1024);
        rt.image.fingerprint = 0xABCDEF12345;
        rt.roots.shared.globals = vec![flint_rt::gc::GlobalSlot::new(flint_rt::value::NIL)];
        let accepted = flint_rt::snap::import_live(&mut rt, &bytes);
        println!("import of {src}: accepted={accepted}");
        if accepted {
            let root = rt.roots.shared.globals[0].get();
            let got = render(&mut rt, root);
            // AGAINST THE WHOLE STRUCTURE, rebuilt here, and not against a
            // prefix. A first version of this checked `accepted` and the first
            // 24 characters, and a byte flipped in the middle of a real port
            // snapshot passed BOTH: the import still succeeded and the first
            // element still read `0=item-0`. The only thing that moved was one
            // character 3 000 along. A gate that cannot see that is not
            // checking the crossing, it is checking the header.
            let mut fresh = Rt::with_heap(1024 * 1024, 64 * 1024 * 1024);
            let want_root = build(&mut fresh, 300);
            let want = render(&mut fresh, want_root);
            println!("  matches={}", got == want);
            println!("  length: {} (want {})", got.len(), want.len());
            if got != want {
                let at = got
                    .chars()
                    .zip(want.chars())
                    .position(|(a, b)| a != b)
                    .unwrap_or(got.len().min(want.len()));
                println!("  first difference at char {at}");
                std::process::exit(1);
            }
        }
        return;
    }
    let path = match std::env::args().nth(1) {
        Some(p) => p,
        None => {
            eprintln!("usage: livedump <out-path>");
            std::process::exit(2);
        }
    };
    // THE SAME RUNTIME THE PORTS BUILD, and this is load-bearing rather than
    // tidy: `RtSnapshot` constructs `new Rt(1024 * 1024, 64 * 1024 * 1024)` and
    // installs NO host natives. A first version of this file used the default
    // sizes and installed them, and the snapshot came out 65 918 bytes against
    // the ports' 33 754 -- which looks exactly like a format divergence and was
    // a different heap being measured.
    let mut rt = Rt::with_heap(1024 * 1024, 64 * 1024 * 1024);
    // THE SAME FINGERPRINT the ports stamp, because it is written into the
    // snapshot and a difference here would be a difference in the bytes that
    // says nothing about the format.
    rt.image.fingerprint = 0xABCDEF12345;
    let root = build(&mut rt, 300);
    rt.roots.shared.globals = vec![flint_rt::gc::GlobalSlot::new(root)];

    let mut out = Vec::new();
    if !flint_rt::snap::export_live(&mut rt, &mut out) {
        eprintln!("livedump: the walk disagreed with the collector");
        std::process::exit(1);
    }
    println!(
        "roots: stack_top={} shadow={} globals={} consts={}",
        rt.roots.stack_top,
        rt.roots.shadow.len(),
        rt.roots.shared.globals.len(),
        rt.roots.shared.consts.len()
    );
    // WHICH OBJECT, when the bytes stop matching. Walking the record layout
    // back out is the only way to turn "they differ at byte 33676" into a
    // statement about what differs.
    if std::env::var("LIVEDUMP_WALK").is_ok() {
        let mut o = 16usize;
        let n = u32::from_le_bytes(out[o..o + 4].try_into().unwrap()) as usize;
        o += 4;
        for i in 0..n {
            let start = o;
            let t = out[o];
            o += 1;
            let len = u32::from_le_bytes(out[o..o + 4].try_into().unwrap()) as usize;
            o += 4;
            match flint_rt::obj::layout_of(t) {
                flint_rt::obj::Layout::Vals => {
                    for _ in 0..len {
                        o += if out[o] == 1 { 1 + 4 } else { 1 + 8 };
                    }
                }
                _ => {
                    let body = u32::from_le_bytes(out[o..o + 4].try_into().unwrap()) as usize;
                    o += 4 + body;
                }
            }
            if start <= 33676 && o > 33600 {
                println!("obj {i}: ty={t} len={len} bytes {start}..{o}");
            }
        }
    }
    println!("live snapshot: {} bytes", out.len());
    std::fs::write(&path, &out).expect("write");
}
