package mill.javalib;

import org.junit.Test;
import static org.junit.Assert.*;

public class ClassLoaderTests {
    @Test
    public void comSunClassesExistInTestsClasspath() throws Exception {
        String javaVersion = System.getProperty("java.specification.version");
        boolean isJava8 = javaVersion.startsWith("1.");
        if (isJava8) {
            // This should not throw ClassNotFoundException on Java 8
            getClass().getClassLoader().loadClass("com.sun.nio.zipfs.ZipFileSystemProvider");
        }
        // On newer Java versions, this test passes trivially
        assertTrue(true);
    }
}
