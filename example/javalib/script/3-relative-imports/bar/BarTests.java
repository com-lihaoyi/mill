//| extends: [mill.script.JavaModule.Junit4]
//| moduleDeps: [./Bar.java]
package bar;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class BarTests {
  @Test
  public void simple() {
    assertEquals("<h1>hello</h1>", Bar.generateHtml("hello"));
  }
}
