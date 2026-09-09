//! Which opcodes does a program actually execute?
//!
//! The question this exists for is coverage: before three runtimes' opcode
//! bodies are REGENERATED from one source (`doc/decisions/0038`), it has to be
//! known which of them the cross-runtime suite would notice a mistake in. An
//! opcode no conformance program reaches can be regenerated wrongly and nothing
//! will say so.
//!
//!     cargo run -p flint-rt --features diagnostics --example opcov -- <img>...
//!
//! Prints one line per image and a census at the end.
use flint_rt::native::Program;
use flint_rt::vm::op;

// `aotstat::read` packs several arrays behind one index; the opcode census
// starts after four histograms and the counters.
const NBUCKET: u32 = 20;
const NCOUNTS: u32 = 28;
const OPS_BASE: u32 = NBUCKET * 4 + NCOUNTS;

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let mut seen = [0u64; 256];
    for path in &args {
        let bytes = match std::fs::read(path) {
            Ok(b) => b,
            Err(e) => {
                eprintln!("{path}: {e}");
                continue;
            }
        };
        let before: Vec<u64> = (0..256)
            .map(|i| {
                Program::load(&bytes, 500_000_000)
                    .ok()
                    .map(|p| p.stat_region(OPS_BASE + i))
                    .unwrap_or(0)
            })
            .collect();
        let _ = before;
        let mut p = match Program::load(&bytes, 500_000_000) {
            Ok(p) => p,
            Err(e) => {
                eprintln!("{path}: {e}");
                continue;
            }
        };
        let out = p.run(&["main"]);
        let mut hit = 0;
        for i in 0..256u32 {
            let n = p.stat_region(OPS_BASE + i);
            if n > 0 {
                hit += 1;
            }
            seen[i as usize] = seen[i as usize].max(n);
        }
        println!("{:<28} code={} opcodes={}", path, out.code, hit);
    }
    // AGAINST THE OPCODES THAT EXIST, not against 256 slots. The census used
    // to report "217 of 256 cold" and that number could not be acted on: six
    // of those slots are opcodes deliberately REMOVED, one range is reserved
    // for superinstructions nothing emits yet, and the rest were never
    // allocated. All three are cold for good reasons, and burying the one
    // question this file exists to answer -- is there a live opcode the suite
    // would not notice a mistake in -- underneath them makes the headline
    // unreadable in the direction that matters.
    //
    // The values come from `vm::op` rather than from a copy, so a renumbering
    // cannot drift. Only an ADDITION can be forgotten here, and the gate fails
    // on a cold one, so a new opcode arrives with the choice made either way.
    let defined: [(&str, u8); 39] = [
        ("CONST", op::CONST), ("NIL", op::NIL), ("TRUE", op::TRUE),
        ("FALSE", op::FALSE), ("INT", op::INT), ("LOCAL", op::LOCAL),
        ("LOCAL_W", op::LOCAL_W), ("SET_LOCAL", op::SET_LOCAL),
        ("SET_LOCAL_W", op::SET_LOCAL_W), ("UPVAL", op::UPVAL),
        ("VAR", op::VAR), ("SET_VAR", op::SET_VAR), ("POP", op::POP),
        ("DUP", op::DUP), ("JUMP", op::JUMP), ("JUMP_IF_FALSE", op::JUMP_IF_FALSE),
        ("CALL", op::CALL), ("TAIL_CALL", op::TAIL_CALL), ("RETURN", op::RETURN),
        ("CLOSURE", op::CLOSURE), ("NATIVE", op::NATIVE), ("THROW", op::THROW),
        ("TRY", op::TRY), ("POP_HANDLER", op::POP_HANDLER), ("RETHROW", op::RETHROW),
        ("VECTOR", op::VECTOR), ("MAP", op::MAP), ("SET", op::SET),
        ("APPLY", op::APPLY), ("SELF", op::SELF), ("ADD_INT", op::ADD_INT),
        ("SUB_INT", op::SUB_INT), ("MUL_INT", op::MUL_INT), ("LT_INT", op::LT_INT),
        ("LE_INT", op::LE_INT), ("GT_INT", op::GT_INT), ("GE_INT", op::GE_INT),
        ("EQ_INT", op::EQ_INT), ("TYPE_P", op::TYPE_P),
    ];
    println!("\n--- opcodes never executed by any of these images ---");
    let cold_defined: Vec<&(&str, u8)> =
        defined.iter().filter(|(_, v)| seen[*v as usize] == 0).collect();
    println!(
        "{} of {} DEFINED opcodes cold",
        cold_defined.len(),
        defined.len()
    );
    for (nm, v) in &cold_defined {
        println!("  cold: {nm} (0x{v:02x})");
    }
    let slots_cold = (0..256usize).filter(|i| seen[*i] == 0).count();
    println!(
        "({slots_cold} of 256 raw slots cold, which includes every opcode never \
         allocated, the six removed ones, and the reserved superinstruction range)"
    );
}
