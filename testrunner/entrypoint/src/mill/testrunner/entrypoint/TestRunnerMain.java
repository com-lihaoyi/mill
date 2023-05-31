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

        URLClassLoader cl = new URLClassLoader(testRunnerClasspath, null){
            public Class<?> findClass(String name) throws ClassNotFoundException {
                if (name.startsWith("sbt.testing")) {
                    return TestRunnerMain.class.getClassLoader().loadClass(name);
                } else {
                    return super.findClass(name);
                }
            }
        };

        Class<?> testRunnerCls = cl.loadClass("mill.testrunner.TestRunner");
        Method mainMethod = testRunnerCls.getMethod("main0", String[].class, ClassLoader.class);

        // Wrap in String[][] to counter varargs expansion
        mainMethod.invoke(null, args, TestRunnerMain.class.getClassLoader());
    }
}
bu
