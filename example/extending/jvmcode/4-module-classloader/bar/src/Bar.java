package bar;

import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Bar {
  public static void main(String[] args) throws IOException {
    Path dest = Paths.get(args[0]);
    String[] sources = Arrays.<String>copyOfRange(args, 1, args.length);
    for (String sourceStr : sources) {
      Path sourcePath = Paths.get(sourceStr).toAbsolutePath();
      try (Stream<Path> paths = Files.walk(sourcePath)) {
        for (Path p : paths.collect(Collectors.toList())) {
          if (p.toString().endsWith(".java")) {
            String mangledText = Files.readString(p).replace("hello", "HELLO");
            Path fileDest = dest.resolve(sourcePath.relativize(p));
            Files.write(fileDest, mangledText.getBytes());
          }
        }
      }
    }
  }
}
