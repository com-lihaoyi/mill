package mill.javalib.testrunner.entrypoint;

import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.stream.Stream;

/**
 * Bootstrap main method to take the actual testrunner classpath as a CLI arg
 * to load into a classloader and instantiate using reflection. This allows us
 * to run user code in the top-level classloader, without worrying about
 * conflict with the testrunner classpath or issues due to user code running in
 * nested classloaders.
 */
public class TestRunnerMain {
  /**
   *
   * @param args arg1: classpath, arg2 testArgs-file
   */
  public static void main(String[] args) throws Exception {
    URL[] testRunnerClasspath = Stream.of(args[0].split(","))
        .map(s -> {
          try {
            return new URL(s);
          } catch (MalformedURLException e) {
            throw new RuntimeException(e);
          }
        })
        .toArray(URL[]::new);

    URLClassLoader cl = new URLClassLoader(testRunnerClasspath, null) {
      public Class<?> findClass(String name) throws ClassNotFoundException {
        if (name.startsWith("sbt.testing")) {
          return TestRunnerMain.class.getClassLoader().loadClass(name);
        } else {
          return super.findClass(name);
        }
      }
    };

    Class<?> testRunnerCls = cl.loadClass("mill.javalib.testrunner.TestRunnerMain0");
    Method mainMethod = testRunnerCls.getMethod("main0", String[].class, ClassLoader.class);

    // Wrap in String[][] to counter varargs expansion
    mainMethod.invoke(null, args, TestRunnerMain.class.getClassLoader());
  }
}
