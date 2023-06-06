package bar;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class BarTest {

    @Test
    public void testValue() {
        assertEquals(271828, Bar.value);
    }
}
