package bar;

import static org.junit.Assert.assertEquals;

import java.nio.file.*;
import org.junit.Test;

public class BarTests {
  @Test
  public void simple() throws Exception {
    String result = Bar.generateHtml("world");
    Path path = Paths.get("generated.html");
    Files.write(path, result.getBytes());
    assertEquals("<p>world</p>", Files.readString(path));
  }
}
