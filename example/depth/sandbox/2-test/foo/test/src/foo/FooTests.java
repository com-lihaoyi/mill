package foo;

import static org.junit.Assert.assertEquals;
import org.junit.Test;
import java.nio.file.*;

public class FooTests {
    @Test
    public void simple() throws Exception {
        String result = Foo.generateHtml("hello");
        Path path = Paths.get("generated.html");
        Files.write(path, result.getBytes());
        assertEquals("<h1>hello</h1>", Files.readString(path));
    }
}
