package mill.client;

import static org.junit.Assert.assertEquals;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import mill.client.Util;
import org.junit.Test;

public class MillEnvTests {

  @Test
  public void readOptsFileLinesWithoutFInalNewline() throws Exception {
    Path file = Paths.get(
        getClass().getClassLoader().getResource("file-wo-final-newline.txt").toURI());
    List<String> lines = Util.readOptsFileLines(file);
    assertEquals(
        lines, Arrays.asList("-DPROPERTY_PROPERLY_SET_VIA_JVM_OPTS=value-from-file", "-Xss120m"));
  }
}
