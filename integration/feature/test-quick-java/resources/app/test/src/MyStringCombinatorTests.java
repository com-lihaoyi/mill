package app;

import org.junit.Test;
import static org.junit.Assert.*;

public class MyStringCombinatorTests {
    @Test
    public void simple() {
        MyString a = new MyString("a");
        MyString b = new MyString("b");
        MyString c = new MyString("c");
        MyString result = MyString.combine(a, b, c);
        assertEquals(new MyString("abc"), result);
    }
}
