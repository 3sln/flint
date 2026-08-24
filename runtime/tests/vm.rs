//! Interpreter tests, driven through the real program-image format so the
//! format and the VM are both exercised.

use flint_rt::image::ImageWriter;
use flint_rt::rt::{sbuf, Rt};
use flint_rt::value::{Value, NIL};
use flint_rt::vm::op;

/// A tiny assembler. Labels are resolved on `done()`.
#[derive(Default)]
struct Asm {
    b: Vec<u8>,
    fixups: Vec<(usize, String)>,
    labels: std::collections::HashMap<String, usize>,
}

impl Asm {
    fn new() -> Asm {
        Default::default()
    }
    fn op(&mut self, o: u8) -> &mut Self {
        self.b.push(o);
        self
    }
    fn u8v(&mut self, v: u8) -> &mut Self {
        self.b.push(v);
        self
    }
    fn u16v(&mut self, v: u16) -> &mut Self {
        self.b.extend_from_slice(&v.to_le_bytes());
        self
    }
    fn i16v(&mut self, v: i16) -> &mut Self {
        self.b.extend_from_slice(&v.to_le_bytes());
        self
    }
    fn konst(&mut self, k: u32) -> &mut Self {
        self.op(op::CONST).u16v(k as u16)
    }
    fn int(&mut self, n: i16) -> &mut Self {
        self.op(op::INT).i16v(n)
    }
    fn local(&mut self, i: u8) -> &mut Self {
        self.op(op::LOCAL).u8v(i)
    }
    fn call(&mut self, n: u8) -> &mut Self {
        self.op(op::CALL).u8v(n)
    }
    fn native(&mut self, idx: u16, argc: u8) -> &mut Self {
        self.op(op::NATIVE).u16v(idx).u8v(argc)
    }
    fn jump(&mut self, o: u8, label: &str) -> &mut Self {
        self.b.push(o);
        self.fixups.push((self.b.len(), label.to_string()));
        self.b.extend_from_slice(&[0, 0]);
        self
    }
    fn label(&mut self, name: &str) -> &mut Self {
        self.labels.insert(name.to_string(), self.b.len());
        self
    }
    fn done(mut self) -> Vec<u8> {
        for (at, name) in &self.fixups {
            let target = *self.labels.get(name).unwrap_or_else(|| panic!("no label {name}")) as i32;
            let rel = target - (*at as i32 + 2);
            let bytes = (rel as i16).to_le_bytes();
            self.b[*at] = bytes[0];
            self.b[*at + 1] = bytes[1];
        }
        self.b
    }
}

fn run(w: &mut ImageWriter, args: Vec<&str>) -> (Rt, Value) {
    let bytes = w.finish();
    let mut rt = Rt::new();
    rt.install_host_natives();
    assert!(rt.load_image(&bytes), "image did not load");
    let base = rt.mark();
    for a in &args {
        let s = rt.string(a);
        rt.push(s);
    }
    let argv = rt.vec_from_roots(base, args.len());
    rt.pop_to(base);
    let r = rt.run_program(argv);
    (rt, r)
}

fn nat(name: &str) -> u32 {
    Rt::host_native_slot(name).unwrap_or_else(|| panic!("no builtin {name}"))
}

#[test]
fn constants_and_arithmetic() {
    let mut w = ImageWriter::new();
    let name = w.k_string("main");
    let add = {
        let c = w.k_string("flint/add");
        w.add_native(c, nat("flint/add"))
    };
    let body = {
        let mut a = Asm::new();
        a.int(2).int(3).native(add as u16, 2).op(op::RETURN);
        a.done()
    };
    w.entry = w.add_fn(name, 1, false, 1, &body);
    let (rt, v) = run(&mut w, vec![]);
    assert_eq!(rt.as_i64(v), Some(5));
}

#[test]
fn arguments_arrive_as_a_vector_of_strings() {
    let mut w = ImageWriter::new();
    let name = w.k_string("main");
    let body = {
        let mut a = Asm::new();
        a.local(0).op(op::RETURN);
        a.done()
    };
    w.entry = w.add_fn(name, 1, false, 1, &body);
    let (mut rt, v) = run(&mut w, vec!["alpha", "beta"]);
    assert!(rt.is_vector(v));
    assert_eq!(rt.vec_count(v), 2);
    let mut b = sbuf();
    let first = rt.vec_nth(v, 0).unwrap();
    assert_eq!(rt.as_str(first, &mut b), Some("alpha"));
}

#[test]
fn branches_and_locals() {
    let mut w = ImageWriter::new();
    let name = w.k_string("main");
    let lt = {
        let c = w.k_string("flint/lt");
        w.add_native(c, nat("flint/lt"))
    };
    let small = w.k_keyword(None, "small");
    let big = w.k_keyword(None, "big");
    let body = {
        let mut a = Asm::new();
        a.int(10).op(op::SET_LOCAL).u8v(1);
        a.local(1).int(5).native(lt as u16, 2);
        a.jump(op::JUMP_IF_FALSE, "else");
        a.konst(small);
        a.jump(op::JUMP, "end");
        a.label("else").konst(big);
        a.label("end").op(op::RETURN);
        a.done()
    };
    w.entry = w.add_fn(name, 1, false, 2, &body);
    let (mut rt, v) = run(&mut w, vec![]);
    let expect = rt.keyword(None, "big");
    assert_eq!(v, expect);
}

#[test]
fn calls_and_closures() {
    let mut w = ImageWriter::new();
    let mul = {
        let c = w.k_string("flint/mul");
        w.add_native(c, nat("flint/mul"))
    };
    let sq_name = w.k_string("square");
    let sq_body = {
        let mut a = Asm::new();
        a.local(0).local(0).native(mul as u16, 2).op(op::RETURN);
        a.done()
    };
    let sq = w.add_fn(sq_name, 1, false, 1, &sq_body);
    let main_name = w.k_string("main");
    let body = {
        let mut a = Asm::new();
        a.op(op::CLOSURE).u16v(sq as u16).u8v(0);
        a.int(7).call(1).op(op::RETURN);
        a.done()
    };
    w.entry = w.add_fn(main_name, 1, false, 1, &body);
    let (rt, v) = run(&mut w, vec![]);
    assert_eq!(rt.as_i64(v), Some(49));
}

#[test]
fn upvalues_are_captured_by_value() {
    let mut w = ImageWriter::new();
    let inner_name = w.k_string("inner");
    let inner_body = {
        let mut a = Asm::new();
        a.op(op::UPVAL).u8v(0).op(op::RETURN);
        a.done()
    };
    let inner = w.add_fn(inner_name, 0, false, 0, &inner_body);
    w.fns[inner as usize][4] = 1; // nupvals

    let main_name = w.k_string("main");
    let body = {
        let mut a = Asm::new();
        a.int(42);
        a.op(op::CLOSURE).u16v(inner as u16).u8v(1);
        a.call(0).op(op::RETURN);
        a.done()
    };
    w.entry = w.add_fn(main_name, 1, false, 1, &body);
    let (rt, v) = run(&mut w, vec![]);
    assert_eq!(rt.as_i64(v), Some(42));
}

#[test]
fn recursion_uses_the_interpreter_frame_stack_not_rusts() {
    let mut w = ImageWriter::new();
    let add = {
        let c = w.k_string("flint/add");
        w.add_native(c, nat("flint/add"))
    };
    let sub = {
        let c = w.k_string("flint/sub");
        w.add_native(c, nat("flint/sub"))
    };
    let numeq = {
        let c = w.k_string("flint/num-eq");
        w.add_native(c, nat("flint/num-eq"))
    };
    let fvar = {
        let c = w.k_string("f");
        w.add_var(c)
    };
    let f_name = w.k_string("f");
    let f_body = {
        let mut a = Asm::new();
        a.local(0).int(0).native(numeq as u16, 2);
        a.jump(op::JUMP_IF_FALSE, "rec");
        a.int(0).op(op::RETURN);
        a.label("rec");
        a.int(1);
        a.op(op::VAR).u16v(fvar as u16);
        a.local(0).int(1).native(sub as u16, 2);
        a.call(1);
        a.native(add as u16, 2).op(op::RETURN);
        a.done()
    };
    let f = w.add_fn(f_name, 1, false, 1, &f_body);
    let init_name = w.k_string("init");
    let init_body = {
        let mut a = Asm::new();
        a.op(op::CLOSURE).u16v(f as u16).u8v(0);
        a.op(op::SET_VAR).u16v(fvar as u16);
        a.op(op::NIL).op(op::RETURN);
        a.done()
    };
    let init = w.add_fn(init_name, 0, false, 0, &init_body);
    w.init.push(init);
    let n3000 = w.k_int(3000);
    let main_name = w.k_string("main");
    let body = {
        let mut a = Asm::new();
        a.op(op::VAR).u16v(fvar as u16);
        a.konst(n3000);
        a.call(1).op(op::RETURN);
        a.done()
    };
    w.entry = w.add_fn(main_name, 1, false, 1, &body);
    let (rt, v) = run(&mut w, vec![]);
    assert!(rt.thrown.is_nil());
    assert_eq!(rt.as_i64(v), Some(3000));
}

#[test]
fn runaway_recursion_throws_instead_of_crashing() {
    let mut w = ImageWriter::new();
    let fvar = {
        let c = w.k_string("f");
        w.add_var(c)
    };
    let f_name = w.k_string("f");
    let f_body = {
        let mut a = Asm::new();
        a.op(op::VAR).u16v(fvar as u16).call(0).op(op::RETURN);
        a.done()
    };
    let f = w.add_fn(f_name, 0, false, 0, &f_body);
    let init_name = w.k_string("init");
    let init_body = {
        let mut a = Asm::new();
        a.op(op::CLOSURE).u16v(f as u16).u8v(0);
        a.op(op::SET_VAR).u16v(fvar as u16);
        a.op(op::NIL).op(op::RETURN);
        a.done()
    };
    let init = w.add_fn(init_name, 0, false, 0, &init_body);
    w.init.push(init);
    let main_name = w.k_string("main");
    let body = {
        let mut a = Asm::new();
        a.op(op::VAR).u16v(fvar as u16).call(0).op(op::RETURN);
        a.done()
    };
    w.entry = w.add_fn(main_name, 1, false, 1, &body);
    let (mut rt, _v) = run(&mut w, vec![]);
    assert!(!rt.thrown.is_nil(), "unbounded recursion must throw, not crash");
    let kind = rt.ex_kind(rt.thrown);
    let mut b = sbuf();
    assert_eq!(rt.as_str(kind, &mut b), Some("StackOverflowError"));
}

#[test]
fn tail_calls_run_in_constant_space() {
    let mut w = ImageWriter::new();
    let sub = {
        let c = w.k_string("flint/sub");
        w.add_native(c, nat("flint/sub"))
    };
    let add = {
        let c = w.k_string("flint/add");
        w.add_native(c, nat("flint/add"))
    };
    let numeq = {
        let c = w.k_string("flint/num-eq");
        w.add_native(c, nat("flint/num-eq"))
    };
    let fvar = {
        let c = w.k_string("go");
        w.add_var(c)
    };
    let f_name = w.k_string("go");
    let f_body = {
        let mut a = Asm::new();
        a.local(0).int(0).native(numeq as u16, 2);
        a.jump(op::JUMP_IF_FALSE, "rec");
        a.local(1).op(op::RETURN);
        a.label("rec");
        a.op(op::VAR).u16v(fvar as u16);
        a.local(0).int(1).native(sub as u16, 2);
        a.local(1).int(1).native(add as u16, 2);
        a.op(op::TAIL_CALL).u8v(2);
        a.done()
    };
    let f = w.add_fn(f_name, 2, false, 2, &f_body);
    let init_name = w.k_string("init");
    let init_body = {
        let mut a = Asm::new();
        a.op(op::CLOSURE).u16v(f as u16).u8v(0);
        a.op(op::SET_VAR).u16v(fvar as u16);
        a.op(op::NIL).op(op::RETURN);
        a.done()
    };
    let init = w.add_fn(init_name, 0, false, 0, &init_body);
    w.init.push(init);
    let n = w.k_int(1_000_000);
    let main_name = w.k_string("main");
    let body = {
        let mut a = Asm::new();
        a.op(op::VAR).u16v(fvar as u16);
        a.konst(n).int(0);
        a.call(2).op(op::RETURN);
        a.done()
    };
    w.entry = w.add_fn(main_name, 1, false, 1, &body);
    let (rt, v) = run(&mut w, vec![]);
    assert!(rt.thrown.is_nil(), "a tail call must not grow the frame stack");
    assert_eq!(rt.as_i64(v), Some(1_000_000));
}

#[test]
fn throw_and_catch() {
    let mut w = ImageWriter::new();
    let msg = w.k_string("boom");
    let caught = w.k_keyword(None, "caught");
    let exinfo = {
        let c = w.k_string("ex-info");
        w.add_native(c, nat("ex-info"))
    };
    let main_name = w.k_string("main");
    let body = {
        let mut a = Asm::new();
        a.jump(op::TRY, "handler");
        a.konst(msg).op(op::NIL).native(exinfo as u16, 2);
        a.op(op::THROW);
        a.op(op::POP_HANDLER);
        a.jump(op::JUMP, "end");
        a.label("handler");
        a.op(op::POP).konst(caught);
        a.label("end").op(op::RETURN);
        a.done()
    };
    w.entry = w.add_fn(main_name, 1, false, 1, &body);
    let (mut rt, v) = run(&mut w, vec![]);
    assert!(rt.thrown.is_nil(), "the handler should have absorbed it");
    let expect = rt.keyword(None, "caught");
    assert_eq!(v, expect);
}

#[test]
fn a_throw_from_a_nested_frame_unwinds_to_the_handler() {
    let mut w = ImageWriter::new();
    let msg = w.k_string("deep");
    let exinfo = {
        let c = w.k_string("ex-info");
        w.add_native(c, nat("ex-info"))
    };
    let exmsg = {
        let c = w.k_string("ex-message");
        w.add_native(c, nat("ex-message"))
    };
    let thrower_name = w.k_string("thrower");
    let t_body = {
        let mut a = Asm::new();
        a.konst(msg).op(op::NIL).native(exinfo as u16, 2).op(op::THROW);
        a.done()
    };
    let thrower = w.add_fn(thrower_name, 0, false, 0, &t_body);
    let main_name = w.k_string("main");
    let body = {
        let mut a = Asm::new();
        a.jump(op::TRY, "handler");
        a.op(op::CLOSURE).u16v(thrower as u16).u8v(0);
        a.call(0);
        a.op(op::POP_HANDLER);
        a.jump(op::JUMP, "end");
        a.label("handler");
        a.native(exmsg as u16, 1);
        a.label("end").op(op::RETURN);
        a.done()
    };
    w.entry = w.add_fn(main_name, 1, false, 1, &body);
    let (mut rt, v) = run(&mut w, vec![]);
    let mut b = sbuf();
    assert_eq!(rt.as_str(v, &mut b), Some("deep"));
}

#[test]
fn collection_literals() {
    let mut w = ImageWriter::new();
    let ka = w.k_keyword(None, "a");
    let kb = w.k_keyword(None, "b");
    let main_name = w.k_string("main");
    let body = {
        let mut a = Asm::new();
        a.konst(ka).int(1).konst(kb).int(2);
        a.op(op::MAP).u16v(2);
        a.op(op::RETURN);
        a.done()
    };
    w.entry = w.add_fn(main_name, 1, false, 1, &body);
    let (mut rt, v) = run(&mut w, vec![]);
    assert!(rt.is_map(v));
    assert_eq!(rt.map_count(v), 2);
    let ka = rt.keyword(None, "a");
    assert_eq!(rt.map_get(v, ka, NIL).as_fixnum(), 1);
}

#[test]
fn variadic_arity_collects_the_rest() {
    let mut w = ImageWriter::new();
    let f_name = w.k_string("f");
    let f_body = {
        let mut a = Asm::new();
        a.local(1).op(op::RETURN);
        a.done()
    };
    let f = w.add_fn(f_name, 1, true, 2, &f_body);
    let main_name = w.k_string("main");
    let body = {
        let mut a = Asm::new();
        a.op(op::CLOSURE).u16v(f as u16).u8v(0);
        a.int(1).int(2).int(3).int(4);
        a.call(4).op(op::RETURN);
        a.done()
    };
    w.entry = w.add_fn(main_name, 1, false, 1, &body);
    let (mut rt, v) = run(&mut w, vec![]);
    assert_eq!(rt.seq_count(v), 3);
    let f0 = rt.first(v);
    assert_eq!(f0.as_fixnum(), 2);
}

#[test]
fn apply_spreads_the_last_argument() {
    let mut w = ImageWriter::new();
    let add = {
        let c = w.k_string("flint/add");
        w.add_native(c, nat("flint/add"))
    };
    let sum_name = w.k_string("sum3");
    let sum_body = {
        let mut a = Asm::new();
        a.local(0).local(1).native(add as u16, 2).local(2).native(add as u16, 2).op(op::RETURN);
        a.done()
    };
    let sum = w.add_fn(sum_name, 3, false, 3, &sum_body);
    let main_name = w.k_string("main");
    let body = {
        let mut a = Asm::new();
        a.op(op::CLOSURE).u16v(sum as u16).u8v(0);
        a.int(10);
        a.int(20).int(30).op(op::VECTOR).u16v(2);
        a.op(op::APPLY).u8v(2);
        a.op(op::RETURN);
        a.done()
    };
    w.entry = w.add_fn(main_name, 1, false, 1, &body);
    let (rt, v) = run(&mut w, vec![]);
    assert_eq!(rt.as_i64(v), Some(60));
}

#[test]
fn keywords_and_maps_are_callable() {
    let mut w = ImageWriter::new();
    let ka = w.k_keyword(None, "a");
    let main_name = w.k_string("main");
    let body = {
        let mut a = Asm::new();
        a.konst(ka);
        a.konst(ka).int(7).op(op::MAP).u16v(1);
        a.call(1).op(op::RETURN);
        a.done()
    };
    w.entry = w.add_fn(main_name, 1, false, 1, &body);
    let (rt, v) = run(&mut w, vec![]);
    assert_eq!(rt.as_i64(v), Some(7));
}

#[test]
fn the_interpreter_survives_collection_at_every_allocation() {
    let mut w = ImageWriter::new();
    let conj = {
        let c = w.k_string("conj");
        w.add_native(c, nat("conj"))
    };
    let sub = {
        let c = w.k_string("flint/sub");
        w.add_native(c, nat("flint/sub"))
    };
    let numeq = {
        let c = w.k_string("flint/num-eq");
        w.add_native(c, nat("flint/num-eq"))
    };
    let gvar = {
        let c = w.k_string("go");
        w.add_var(c)
    };
    let g_name = w.k_string("go");
    let g_body = {
        let mut a = Asm::new();
        a.local(0).int(0).native(numeq as u16, 2);
        a.jump(op::JUMP_IF_FALSE, "rec");
        a.local(1).op(op::RETURN);
        a.label("rec");
        a.op(op::VAR).u16v(gvar as u16);
        a.local(0).int(1).native(sub as u16, 2);
        a.local(1).local(0).native(conj as u16, 2);
        a.op(op::TAIL_CALL).u8v(2);
        a.done()
    };
    let g = w.add_fn(g_name, 2, false, 2, &g_body);
    let init_name = w.k_string("init");
    let init_body = {
        let mut a = Asm::new();
        a.op(op::CLOSURE).u16v(g as u16).u8v(0);
        a.op(op::SET_VAR).u16v(gvar as u16);
        a.op(op::NIL).op(op::RETURN);
        a.done()
    };
    let init = w.add_fn(init_name, 0, false, 0, &init_body);
    w.init.push(init);
    let main_name = w.k_string("main");
    let body = {
        let mut a = Asm::new();
        a.op(op::VAR).u16v(gvar as u16);
        a.int(300).op(op::VECTOR).u16v(0);
        a.op(op::TAIL_CALL).u8v(2);
        a.done()
    };
    w.entry = w.add_fn(main_name, 1, false, 1, &body);

    let bytes = w.finish();
    let mut rt = Rt::new();
    rt.install_host_natives();
    assert!(rt.load_image(&bytes));
    rt.gc.stress = true;
    let empty = rt.empty_vec();
    let v = rt.run_program(empty);
    assert!(rt.thrown.is_nil(), "threw under GC stress");
    assert_eq!(rt.vec_count(v), 300);
    assert_eq!(rt.vec_nth(v, 0).unwrap().as_fixnum(), 300);
    assert_eq!(rt.vec_nth(v, 299).unwrap().as_fixnum(), 1);
}

/// Regression: a frame used to cache its closure, and that copy was a root the
/// collector could not see. After a collection moved the closure, `UPVAL` read
/// a stale address. The symptom was arbitrary -- a number in function position,
/// a nonsense frame trace -- and it only showed up once programs were big
/// enough to collect mid-call.
#[test]
fn upvalues_survive_a_collection_taken_mid_call() {
    let mut w = ImageWriter::new();
    let conj = {
        let c = w.k_string("conj");
        w.add_native(c, nat("conj"))
    };
    let sub = {
        let c = w.k_string("flint/sub");
        w.add_native(c, nat("flint/sub"))
    };
    let numeq = {
        let c = w.k_string("flint/num-eq");
        w.add_native(c, nat("flint/num-eq"))
    };
    // (fn [captured] (fn [n acc] (if (= n 0) captured (recur (dec n) (conj acc captured)))))
    // The inner fn allocates on every iteration, so a collection is certain to
    // land while its frame is live, and its only reference to `captured` is the
    // upvalue.
    let inner_name = w.k_string("inner");
    let inner_body = {
        let mut a = Asm::new();
        a.label("top");
        a.local(0).int(0).native(numeq as u16, 2);
        a.jump(op::JUMP_IF_FALSE, "rec");
        a.op(op::UPVAL).u8v(0).op(op::RETURN);
        a.label("rec");
        // acc = (conj acc upval)
        a.local(1).op(op::UPVAL).u8v(0).native(conj as u16, 2);
        a.op(op::SET_LOCAL).u8v(1);
        // n = (dec n)
        a.local(0).int(1).native(sub as u16, 2);
        a.op(op::SET_LOCAL).u8v(0);
        a.jump(op::JUMP, "top");
        a.done()
    };
    let inner = w.add_fn(inner_name, 2, false, 2, &inner_body);
    w.fns[inner as usize][4] = 1; // one upvalue

    let outer_name = w.k_string("outer");
    let outer_body = {
        let mut a = Asm::new();
        a.local(0);
        a.op(op::CLOSURE).u16v(inner as u16).u8v(1);
        a.op(op::RETURN);
        a.done()
    };
    let outer = w.add_fn(outer_name, 1, false, 1, &outer_body);

    let marker = w.k_string("a captured string long enough to be a heap object");
    let main_name = w.k_string("main");
    let body = {
        let mut a = Asm::new();
        a.op(op::CLOSURE).u16v(outer as u16).u8v(0);
        a.konst(marker);
        a.call(1); // -> the inner closure
        a.int(2000).op(op::VECTOR).u16v(0);
        a.call(2);
        a.op(op::RETURN);
        a.done()
    };
    w.entry = w.add_fn(main_name, 1, false, 1, &body);

    let bytes = w.finish();
    // A small nursery guarantees collections during the loop.
    let mut rt = Rt::with_heap(64 * 1024, 64 * 1024 * 1024);
    rt.install_host_natives();
    assert!(rt.load_image(&bytes));
    let empty = rt.empty_vec();
    let v = rt.run_program(empty);
    assert!(rt.thrown.is_nil(), "threw: {:?}", rt.ex_message(rt.thrown));
    let mut b = sbuf();
    assert_eq!(
        rt.as_str(v, &mut b),
        Some("a captured string long enough to be a heap object"),
        "the upvalue was read through a stale pointer"
    );
}

/// `run_program` used to hold the argument vector in a Rust local across every
/// module initialiser. Initialisers allocate, so the vector moved and the local
/// became stale -- and a stale pointer is far worse than a wrong argument: when
/// its address is later reused by an unrelated object, the collector treats it
/// as an object start and stamps a forwarding header into the middle of that
/// object. That is how one unrooted local corrupted the *scheduler* and
/// deadlocked a program with no apparent connection to it (`doc/HANDOFF.md`).
///
/// The negative control is the address assertion: if the vector did not move
/// during initialisation the test would pass vacuously, so it checks that it
/// DID move. With the fix removed, that same movement is what makes the
/// argument stale.
#[test]
fn arguments_survive_the_module_initialisers() {
    let mut w = ImageWriter::new();
    let k = w.k_string("padding-so-the-initialiser-allocates");

    // An initialiser that allocates: it builds a vector of fresh strings and
    // drops it, which is enough to move anything younger under stress.
    let init_fn = {
        let mut a = Asm::new();
        for _ in 0..8 {
            a.op(op::CONST).u16v(k as u16);
        }
        a.op(op::VECTOR).u16v(8);
        a.op(op::RETURN);
        let body = a.done();
        let n = w.k_string("init");
        w.add_fn(n, 0, false, 2, &body)
    };
    w.init.push(init_fn);

    // The entry function simply hands its argument vector back.
    let body = {
        let mut a = Asm::new();
        a.op(op::LOCAL).u8v(0).op(op::RETURN);
        a.done()
    };
    let n = w.k_string("main");
    w.entry = w.add_fn(n, 1, false, 2, &body);

    let bytes = w.finish();
    let mut rt = Rt::new();
    rt.install_host_natives();
    assert!(rt.load_image(&bytes), "image did not load");
    rt.gc.stress = true; // collect at every allocation

    let base = rt.mark();
    let s = rt.string("the-one-argument");
    rt.push(s);
    let argv = rt.vec_from_roots(base, 1);
    rt.pop_to(base); // exactly what the ABI does: the caller does NOT keep it rooted
    let before = argv.as_heap();

    let r = rt.run_program(argv);
    assert!(!rt.failed(), "threw while running");

    // It came back intact rather than as whatever now occupies its old address.
    let first = rt.vec_nth(r, 0).expect("argument vector should have one element");
    let mut b = sbuf();
    assert_eq!(rt.as_str(first, &mut b), Some("the-one-argument"));

    // No stale pointer was followed anywhere. This fails AT the collection
    // rather than wherever the corruption happens to surface.
    assert_eq!(
        rt.gc.bad_forward, 0,
        "forward() was asked to treat a non-object as an object start"
    );

    // The control. Under stress every allocation collects, so a vector that
    // survived initialisation MUST have moved. Getting the original address
    // back means a stale pointer was carried through -- which is exactly what
    // removing the rooting in `run_program` does.
    assert_ne!(
        before,
        r.as_heap(),
        "the entry function was handed the argument vector at its ORIGINAL \
         address, so it was given a stale pointer"
    );
}
