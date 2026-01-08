package app;

import org.junit.Test;
import static org.junit.Assert.*;

public class MyNumberDefaultValueTests {
    @Test
    public void simple() {
        MyNumber result = MyNumber.getDefaultValue();
        assertEquals(new MyNumber(0), result);
    }
}
