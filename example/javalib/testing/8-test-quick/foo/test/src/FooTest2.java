package foo;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class FooTest2 {
    @Test
    public void testBar() {
        assertEquals(1, Foo.bar());
    }
}
