package mill.constants;

public class CacheFiles {
  /** Prefix for all cache files. */
  public static final String prefix = "mill-";

  public static String filename(String name) {
    return prefix + name;
  }

  public static final String javaHome = "java-home";

  /** Caches the classpath for the mill runner. */
  public static final String resolveRunner = "resolve-runner";
}
