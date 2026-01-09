package foo;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class FooTest1 {
    @Test
    public void testQux() {
        assertEquals(100, Foo.qux());
    }
}
