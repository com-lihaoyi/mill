package foo;

import org.junit.Test;
import static org.junit.Assert.*;

public class FooTest1 {
    @Test
    public void test1() {
        String name = "Aether";
        String greeted = Foo.greet(name);
        assertEquals("Hello, " + name + "!", greeted);
    }
}