package hello;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class Junit5TestsA {

    @Test
    public void coreTest() {
        assertEquals(Core.msg(), "Hello World");
    }

    @Test
    @Disabled("for demonstration purposes")
    public void skippedTest() {
        // not executed
    }

    @ParameterizedTest
    @ValueSource(strings = { "racecar", "radar" })
    void palindromes(String candidate) {
        assertTrue(isPalindrome(candidate));
    }

    static boolean isPalindrome(String str) {
        String reversed = new StringBuilder(str).reverse().toString();
        return str.equals(reversed);
    }
}

class Junit5TestsB {

    @Test
    void packagePrivateTest() {
        assertEquals(Core.msg(), "Hello World");
    }
}