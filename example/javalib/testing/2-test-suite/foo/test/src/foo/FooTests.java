package foo;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

import org.junit.Test;

public class FooTests {

  @Test
  public void hello() {
    String result = new Foo().hello();
    assertTrue(result.startsWith("Hello"));
  }

  @Test
  public void world() {
    String result = new Foo().hello();
    assertTrue(result.endsWith("World"));
  }

  @Test
  public void testMockito() {
    Foo mockFoo = mock(Foo.class);

    when(mockFoo.hello()).thenReturn("Hello Mockito World");

    String result = mockFoo.hello();

    assertTrue(result.equals("Hello Mockito World"));
    verify(mockFoo).hello();
  }
}
