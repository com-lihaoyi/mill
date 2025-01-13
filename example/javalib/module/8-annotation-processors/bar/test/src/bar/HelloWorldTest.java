package bar;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HelloWorldTest {
  @Test
  public void testSimple() {
    GetterSetterExample exampleValue = new GetterSetterExample();
    assertEquals(exampleValue.getAge(), 10);
    exampleValue.setAge(20);
    assertEquals(exampleValue.getAge(), 20);
  }
}
