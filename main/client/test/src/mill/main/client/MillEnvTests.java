package mill.main.client;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class MillEnvTests {

  @Test
  public void readOptsFileLinesWithoutFInalNewline() throws Exception {
    File file = new File(
        getClass().getClassLoader().getResource("file-wo-final-newline.txt").toURI());
    List<String> lines = Util.readOptsFileLines(file);
    assertEquals(
        lines, Arrays.asList("-DPROPERTY_PROPERLY_SET_VIA_JVM_OPTS=value-from-file", "-Xss120m"));
  }
}
