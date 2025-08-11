package mill.client;

import static org.junit.Assert.assertEquals;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class MillEnvTests {

  @Test
  public void readOptsFileLinesWithoutFinalNewline() throws Exception {
    Path file = Paths.get(
        getClass().getClassLoader().getResource("file-wo-final-newline.txt").toURI());
    Map<String, String> env = Map.of();
    List<String> lines = ClientUtil.readOptsFileLines(file, env);
    assertEquals(
        lines, Arrays.asList("-DPROPERTY_PROPERLY_SET_VIA_JVM_OPTS=value-from-file", "-Xss120m"));
  }
}
