package hello;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class MyAppTests {

    @Test
    public void coreTest() {
        assertEquals(Core.msg(), "Hello World");
    }

    @Test
    public void appTest() {
        assertEquals(Main.getMessage(new String[]{"lols"}), "Hello World lols");
    }

}