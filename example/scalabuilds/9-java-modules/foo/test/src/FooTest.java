package foo;
import static org.junit.Assert.assertEquals;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

public class FooTest {

    @Test
    public void testValue() {
        assertEquals(31337, Foo.value);
    }

    @Test
    public void testMain() {
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));

        String expectedOutput = "\n\n";
        Foo.main(new String[]{});

        String outString = outContent.toString();
        assertTrue(outString.contains("Foo.value: 31337"));
        assertTrue(outString.contains("Bar.value: 271828"));
    }
}
