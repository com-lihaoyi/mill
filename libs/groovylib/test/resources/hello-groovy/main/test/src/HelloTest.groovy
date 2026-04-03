package hello.tests

import hello.Hello
import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.assertEquals

class HelloTest {
    @Test
    void testSuccess() {
        assertEquals("Hello, world!", Hello.getHelloString())
    }
}
