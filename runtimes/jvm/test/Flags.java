import com.flint.Img;
import com.flint.Vm;
import java.nio.file.Files;
import java.nio.file.Path;

/// What the compiler decided, and whether this runtime acted on it.
///
/// `:optimize [perf]` has to mean the same thing on all three runtimes and
/// cannot be carried the same way on any two: on wasm it changes the artifact,
/// here the bytecode is emitted at load time from the same image. So the image
/// carries the decision and the Vm reads it -- and this prints both halves so
/// the claim is checked rather than asserted in a comment.
public class Flags {
    public static void main(String[] args) throws Exception {
        Img img = Img.read(Files.readAllBytes(Path.of(args[0])));
        Vm vm = new Vm(img);
        System.out.println("flags=" + img.flags + " aot=" + vm.aotEnabled);
    }
}
