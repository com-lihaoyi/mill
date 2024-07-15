package foo;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class FooTest {
    @Test
    public void testSimple() {
        assertEquals(Foo.generateHtml("hello"),  "<h1>hello</h1>");
    }

    @Test
    public void testEscaping() {
        assertEquals(Foo.generateHtml("<hello>"),  "<h1>&lt;hello&gt;</h1>");
    }
}
