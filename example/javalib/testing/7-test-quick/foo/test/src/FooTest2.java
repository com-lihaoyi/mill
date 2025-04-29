package foo;

import org.junit.Test;
import static org.junit.Assert.*;

public class FooTest2 {
    @Test
    public void test2() {
        String name = "Bob";
        String greeted = Foo.greet2(name);
        assertEquals("Hi, " + name + "!", greeted);
    }
}