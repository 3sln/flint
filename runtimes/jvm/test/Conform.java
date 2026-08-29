import com.flint.*;

/// Run an image and print what it returned.
///
/// The point of the shape: the SAME source is compiled once and run on both
/// runtimes, and the answers are diffed. `doc/decisions/0010` is explicit that
/// the bytecode makes a port cheap and does nothing to make two ports AGREE --
/// the agreement is this.
public class Conform {
    public static void main(String[] args) throws Exception {
        byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(args[0]));
        Vm vm = new Vm(Img.read(bytes));
        vm.ensureStarted();
        Object out = vm.runProgram(new Vm.Closure(vm.img.entry, new Object[0]),
                             new Object[]{ java.util.List.of() });
        System.out.println(Builtins.str(out));
    }
}
