package mill.constants;

import java.io.IOException;
import java.nio.file.*;

/**
 * Used to add `println`s in scenarios where you can't figure out where on earth
 * your stdout/stderr/logs are going, and so we just dump them in a file in your
 * home folder so you can find them
 */
public class DebugLog {
  public static synchronized void apply(String s) {
    println(s);
  }

  public static synchronized void println(String s) {
    if (System.getenv("CI") != null) {
      throw new RuntimeException("DebugLog cannot be run in CI");
    }
    
    Path path = Paths.get(System.getProperty("user.home"), "mill-debug-log.txt");
    try {
      if (!Files.exists(path)) Files.createFile(path);
      Files.writeString(path, s + "\n", StandardOpenOption.APPEND);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
