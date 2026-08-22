//! Prints `flint.conc`'s unit manifest, generated from the same catalogue that
//! defines the builtins so the two cannot drift apart.

fn main() {
    println!("{{:flint/unit 1");
    println!(" :name flint.conc");
    println!(" :kind :wasm-object");
    println!(" :artifact \"conc.o\"");
    println!(" :requires [flint.rt]");
    println!(" :abi {{:runtime 1 :value 1 :image 1}}");
    // Host-facing exports. Only on the link line when this unit is linked,
    // which is what keeps a pure module's outside edge exactly as it was.
    println!(
        " :exports [\"flint_drain\" \"flint_events_ptr\" \"flint_continue\" \
\"flint_in_alloc\" \"flint_deliver\" \"flint_close\" \"flint_resume\"]"
    );
    println!(" :provides {{");
    for (name, symbol) in flint_conc::CATALOGUE {
        println!("  {:?} {{:symbol {:?}}}", name, symbol);
    }
    println!(" }}}}");
}
