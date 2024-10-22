package foo;

import org.junit.Test;
import static org.junit.Assert.*;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;

public class FooTests {

  @Test
  public void simple() throws IOException {
    // Reference app module's `Foo` class which reads `file.txt` from classpath
    String appClasspathResourceText = Foo.classpathResourceText();
    assertEquals("Hello World Resource File", appClasspathResourceText);

    // Read `test-file-a.txt` from classpath
    String testClasspathResourceText;
    try (InputStream inputStream = Foo.class.getClassLoader().getResourceAsStream("test-file-a.txt")) {
      testClasspathResourceText = new String(inputStream.readAllBytes());
    }
    assertEquals("Test Hello World Resource File A", testClasspathResourceText);

    // Use `MILL_TEST_RESOURCE_DIR` to read `test-file-b.txt` from filesystem
    Path testFileResourceDir = Paths.get(System.getenv("MILL_TEST_RESOURCE_DIR"));
    String testFileResourceText = Files.readString(
        testFileResourceDir.resolve("test-file-b.txt")
    );
    assertEquals("Test Hello World Resource File B", testFileResourceText);

    // Use `MILL_TEST_RESOURCE_DIR` to list files available in resource folder
    List<Path> actualFiles = new ArrayList<>(Files.list(testFileResourceDir).toList());
    actualFiles.sort(Path::compareTo);
    List<Path> expectedFiles = List.of(
            testFileResourceDir.resolve("test-file-a.txt"),
            testFileResourceDir.resolve("test-file-b.txt")
    );
    assertEquals(expectedFiles, actualFiles);

    // Use the `OTHER_FILES_DIR` configured in your build to access the
    // files in `foo/test/other-files/`.
    String otherFileText = Files.readString(
        Paths.get(System.getenv("OTHER_FILES_DIR"), "other-file.txt")
    );
    assertEquals("Other Hello World File", otherFileText);
  }
}
