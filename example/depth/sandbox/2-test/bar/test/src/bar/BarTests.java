package bar;

import static org.junit.Assert.assertEquals;
import org.junit.Test;
import java.nio.file.*;

public class BarTests {
    @Test
    public void simple() throws Exception {
        String result = Bar.generateHtml("world");
        Path path = Paths.get("generated.html");
        Files.write(path, result.getBytes());
        assertEquals("<p>world</p>", new String(Files.readAllBytes(path)));
    }
}