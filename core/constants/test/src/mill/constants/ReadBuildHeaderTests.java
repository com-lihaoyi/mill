package mill.constants;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import mill.api.daemon.MillException;
import org.junit.Test;

public class ReadBuildHeaderTests {
  @Test
  public void shebangThenYamlHeader() throws Exception {
    Path dir = Files.createTempDirectory("mill-header");
    Path build = dir.resolve("build.mill");
    Files.writeString(
        build, "#!/usr/bin/env mill\n//| mill-version: 1.2.3\n//| mill-jvm-version: 11\n");
    String header = Util.readBuildHeader(build, "build.mill");
    assertEquals("mill-version: 1.2.3\nmill-jvm-version: 11", header);
  }

  @Test
  public void yamlHeaderWithoutShebang() throws Exception {
    Path dir = Files.createTempDirectory("mill-header");
    Path build = dir.resolve("build.mill");
    Files.writeString(build, "//| mill-version: 1.2.3\n");
    String header = Util.readBuildHeader(build, "build.mill");
    assertEquals("mill-version: 1.2.3", header);
  }

  @Test
  public void shebangDoesNotAllowYamlAfterCode() throws Exception {
    Path dir = Files.createTempDirectory("mill-header");
    Path build = dir.resolve("build.mill");
    Files.writeString(build, "#!/usr/bin/env mill\nimport mill._\n//| mill-version: 1.2.3\n");
    try {
      Util.readBuildHeader(build, "build.mill");
      throw new AssertionError("expected MillException");
    } catch (MillException e) {
      assertTrue(
          e.getMessage().contains("YAML header comments can only occur at the start of the file"));
    }
  }
}
