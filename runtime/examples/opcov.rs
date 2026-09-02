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
    println!("\n--- opcodes never executed by any of these images ---");
    let mut cold = Vec::new();
    for i in 0..256usize {
        if seen[i] == 0 {
            cold.push(i);
        }
    }
    println!("{} of 256 slots cold", cold.len());
    print!("cold: ");
    for i in &cold {
        print!("0x{:02x} ", i);
    }
    println!();
}
