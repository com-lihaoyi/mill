package bar;

import java.nio.file.*;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Bar {
  static Path dest;
  static String[] sources;
  static Path sourcePath;
  static String mangledText;
  static Path fileDest;

  public static void main(String[] args) throws Exception {
    Thread.sleep(1000); // Simulate a slow program that takes some time
    dest = Paths.get(args[0]);
    sources = Arrays.<String>copyOfRange(args, 1, args.length);
    for (String sourceStr : sources) {
      sourcePath = Paths.get(sourceStr).toAbsolutePath();
      try (Stream<Path> paths = Files.walk(sourcePath)) {
        for (Path p : paths.collect(Collectors.toList())) {
          if (p.toString().endsWith(".java")) {
            mangledText = Files.readString(p).replace("hello", "HELLO");
            fileDest = dest.resolve(sourcePath.relativize(p));
            Files.write(fileDest, mangledText.getBytes());
          }
        }
      }
    }
  }
}
