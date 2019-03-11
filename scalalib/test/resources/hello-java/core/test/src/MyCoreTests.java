package hello;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class MyCoreTests {
    @Test
    public void msgTest() {
        assertEquals(Core.msg(), "Hello World!!");
    }
    @Test
    public void lengthTest() {
        assertEquals(Core.msg().length(), 11);
    }
}