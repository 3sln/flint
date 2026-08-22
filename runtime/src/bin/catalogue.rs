//! Prints the builtin catalogue as EDN, for the unit manifest.
//!
//! The catalogue is data (names only, no function pointers), so emitting it does
//! not pin anything in a wasm build -- and generating the manifest from the same
//! declaration that defines the builtins means the two cannot drift apart.

fn main() {
    println!("{{:flint/unit 1");
    println!(" :name flint.rt");
    println!(" :kind :wasm-object");
    println!(" :artifact \"flint_rt.o\"");
    println!(" :requires []");
    println!(" :abi {{:runtime 1 :value 1 :image 1}}");
    println!(" :provides {{");
    for (name, symbol) in flint_rt::builtins::CATALOGUE {
        println!("  {:?} {{:symbol {:?}}}", name, symbol);
    }
    println!(" }}}}");
}
