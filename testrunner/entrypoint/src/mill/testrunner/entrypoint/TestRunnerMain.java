package mill.testrunner.entrypoint;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.URLClassLoader;
import java.net.URL;
import java.net.MalformedURLException;
import java.util.stream.Stream;

public class TestRunnerMain{
    public static void main(String[] args) throws Exception{
        URL[] testRunnerClasspath =
            Stream.of(args[0].split(",")).map(s -> {
                try {
                    return new URL(s);
                }catch(MalformedURLException e){
                    throw new RuntimeException(e);
                }
            }).toArray(URL[]::new);

        boolean java9OrAbove = !System.getProperty("java.specification.version").startsWith("1.");
        ClassLoader parentCl = null;
        if (java9OrAbove) {
            // Make sure when `parent == null`, we only delegate java.* classes
            // to the parent getPlatformClassLoader. This is necessary because
            // in Java 9+, somehow the getPlatformClassLoader ends up with all
            // sorts of other non-java stuff on it's classpath, which is not what
            // we want for an "isolated" classloader!
            parentCl = (ClassLoader)ClassLoader.class.getMethod("getPlatformClassLoader").invoke(null);

        } else {
            // With Java 8 we want a clean classloader that still contains classes
            // coming from com.sun.* etc.
            // We get the application classloader parent which happens to be of
            // type sun.misc.Launcher$ExtClassLoader
            // We can't call the method directly since it would not compile on Java 9+
            // So we load it via reflection to allow compilation in Java 9+ but only
            // on Java 8
            Class<?> launcherClass = TestRunnerMain.class.getClassLoader().loadClass("sun.misc.Launcher");
            Method getLauncherMethod = launcherClass.getMethod("getLauncher");
            Object launcher = getLauncherMethod.invoke(null);
            Method getClassLoaderMethod = launcher.getClass().getMethod("getClassLoader");
            ClassLoader appClassLoader = (ClassLoader)getClassLoaderMethod.invoke(launcher);
            parentCl = appClassLoader.getParent();
        }
        URLClassLoader cl = new URLClassLoader(testRunnerClasspath, parentCl);

        Class<?> testRunnerCls = cl.loadClass("mill.testrunner.TestRunner");
        Method mainMethod = testRunnerCls.getMethod("main0", String[].class);

        // Wrap in String[][] to counter varargs expansion
        mainMethod.invoke(null, new String[][]{args});
    }
}
