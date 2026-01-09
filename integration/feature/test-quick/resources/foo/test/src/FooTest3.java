package foo;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

// This test depends on the bar module through Foo.barValue()
public class FooTest3 {
    @Test
    public void testBarValue() {
        assertEquals(42, Foo.barValue());
    }
}
