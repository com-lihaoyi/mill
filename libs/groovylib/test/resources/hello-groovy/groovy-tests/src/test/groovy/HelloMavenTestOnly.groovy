package hello.maven.tests

//import hello.Hello
import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.assertEquals

class HelloMavenTestOnly {
    @Test
    void testSuccess() {
        assertEquals(true, true)
    }
}
