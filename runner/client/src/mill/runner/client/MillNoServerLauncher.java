package mill.runner.client;

import java.lang.reflect.Method;
import java.util.Optional;

class MillNoServerLauncher {

  public static class LoadResult {

    public final Optional<Method> millMainMethod;
    public final boolean canLoad;
    public final long loadTime;

    public LoadResult(Optional<Method> millMainMethod, final long loadTime) {
      this.millMainMethod = millMainMethod;
      this.canLoad = millMainMethod.isPresent();
      this.loadTime = loadTime;
    }
  }

  private static Optional<LoadResult> canLoad = Optional.empty();

  public static LoadResult load() {
    if (canLoad.isPresent()) {
      return canLoad.get();
    } else {
      long startTime = System.currentTimeMillis();
      Optional<Method> millMainMethod = Optional.empty();
      try {
        Class<?> millMainClass =
            MillNoServerLauncher.class.getClassLoader().loadClass("mill.runner.MillMain");
        Method mainMethod = millMainClass.getMethod("main", String[].class);
        millMainMethod = Optional.of(mainMethod);
      } catch (ClassNotFoundException | NoSuchMethodException e) {
        millMainMethod = Optional.empty();
      }

      long loadTime = System.currentTimeMillis() - startTime;
      LoadResult result = new LoadResult(millMainMethod, loadTime);
      canLoad = Optional.of(result);
      return result;
    }
  }

  public static void runMain(String[] args) throws Exception {
    LoadResult loadResult = load();
    if (loadResult.millMainMethod.isPresent()) {
      int exitVal = MillProcessLauncher.launchMillNoServer(args);
      System.exit(exitVal);
    } else {
      throw new RuntimeException("Cannot load mill.runner.MillMain class");
    }
  }
}
