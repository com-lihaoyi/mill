package app;

import org.junit.Test;
import static org.junit.Assert.*;

public class MyStringDefaultValueTests {
    @Test
    public void simple() {
        MyString result = MyString.getDefaultValue();
        assertEquals(new MyString(""), result);
    }
}
