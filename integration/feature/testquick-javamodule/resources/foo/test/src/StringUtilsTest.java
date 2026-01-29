package foo;

import org.junit.Test;
import static org.junit.Assert.*;

public class StringUtilsTest {
    @Test
    public void testReverse() {
        StringUtils utils = new StringUtils();
        assertEquals("cba", utils.reverse("abc"));
    }

    @Test
    public void testToUpperCase() {
        StringUtils utils = new StringUtils();
        assertEquals("HELLO", utils.toUpperCase("hello"));
    }
}
