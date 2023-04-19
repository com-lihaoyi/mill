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

        String expectedOutput = "Foo.value: 31337\nBar.value: 271828\n";
        Foo.main(new String[]{});

        assertEquals(expectedOutput, outContent.toString());
    }
}
