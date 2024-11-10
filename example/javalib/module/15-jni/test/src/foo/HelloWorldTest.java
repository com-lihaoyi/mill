package foo;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class HelloWorldTest {
    @Test
    public void testSimple() {
        assertEquals(new HelloWorld().sayHello(), "Hello, World!");
    }
}
