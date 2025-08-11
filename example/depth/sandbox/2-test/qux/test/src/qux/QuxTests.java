package qux;

import static org.junit.Assert.assertEquals;

import java.nio.file.*;
import java.util.stream.Collectors;
import org.junit.Test;

public class QuxTests {
  @Test
  public void simple() throws Exception {
    String workspaceRoot = System.getenv("MILL_WORKSPACE_ROOT");

    for (Path subpath : Files.list(Paths.get(workspaceRoot)).collect(Collectors.toList())) {
      String result = Qux.generateHtml(subpath.getFileName().toString());
      Path tmppath = Paths.get(subpath.getFileName() + ".html");
      Files.write(tmppath, result.getBytes());
      assertEquals("<h1>" + subpath.getFileName() + "</h1>", Files.readString(tmppath));
    }
  }
}
