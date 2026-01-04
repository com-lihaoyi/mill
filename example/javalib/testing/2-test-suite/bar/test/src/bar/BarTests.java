package bar;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

import org.junit.Test;

public class BarTests {

  @Test
  public void hello() {
    String result = new Bar().hello();
    assertTrue(result.startsWith("Hello"));
  }

  @Test
  public void world() {
    String result = new Bar().hello();
    assertTrue(result.endsWith("World"));
  }

  @Test
  public void testMockito() {
    Bar mockBar = mock(Bar.class);

    when(mockBar.hello()).thenReturn("Hello Mockito World");

    String result = mockBar.hello();

    assertTrue(result.equals("Hello Mockito World"));
    verify(mockBar).hello();
  }
}
