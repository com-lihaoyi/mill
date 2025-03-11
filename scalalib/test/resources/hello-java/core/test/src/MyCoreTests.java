package hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
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

    @Test
    public void java11Test() {
        String version = System.getProperty("java.version");
        int dot = version.indexOf(".");
        assertNotEquals(dot, -1);
        System.out.println(version);
        assertEquals(version.substring(0, dot), "11");
    }

    @Test
    public void java17Test() {
        String version = System.getProperty("java.version");
        int dot = version.indexOf(".");
        assertNotEquals(dot, -1);
        System.out.println(version);
        assertEquals(version.substring(0, dot), "17");
    }
}
