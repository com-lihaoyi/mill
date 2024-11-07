package hello;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class MyCoreTests {
    @Test
    public void msgTest() {
        System.out.println("Running msgTest");  // Add debug
        String expected = "Hello World!!";
        String actual = Core.msg();
        System.out.println("Expected: " + expected);  // Add debug
        System.out.println("Actual: " + actual);      // Add debug
        assertEquals(expected, actual);
    }
    @Test
    public void lengthTest() {
        System.out.println("Running lengthTest");  // Add debug
        assertEquals(Core.msg().length(), 11);
    }
}