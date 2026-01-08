package app;

import org.junit.Test;
import static org.junit.Assert.*;

public class MyNumberCombinatorTests {
    @Test
    public void simple() {
        MyNumber a = new MyNumber(1);
        MyNumber b = new MyNumber(2);
        MyNumber c = new MyNumber(3);
        MyNumber result = MyNumber.combine(a, b, c);
        assertEquals(new MyNumber(6), result);
    }
}
