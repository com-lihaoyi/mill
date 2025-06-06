import java.io.FileOutputStream;
import java.io.PrintStream;

public class App {

  public static void main(String[] args) throws Exception {
    System.out.println("This is App");
    if (args.length > 0) {
      if(args[0].startsWith("file=")) {
        final var fileName = args[0].split("=", 2)[1];
        System.out.println("Writing to file: " + fileName);
        try (
          final var fs = new FileOutputStream(fileName);
          final var ps = new PrintStream(fs)
        ) {
          if(args.length > 1) {
            if(args[1].startsWith("content=")) {
              final var content = args[1].split("=", 2)[1];
              ps.println(content);
            }
          }
          ps.flush();
        }
      }
    }
  }

}
