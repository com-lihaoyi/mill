package hello;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

public class Junit5Tests {

    @Test
    public void coreTest() {
        assertEquals(Core.msg(), "Hello World");
    }

    @Test
    @Disabled("for demonstration purposes")
    public void skippedTest() {
        // not executed
    }

}