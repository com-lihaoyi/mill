package foo;

import org.junit.Test;

public class RandomTestsA extends RandomTestsUtils {
    @Test
    public void test1() throws Exception { testGreeting("Storm", 38); }
    @Test
    public void test2() throws Exception { testGreeting("Bella", 25); }
    @Test
    public void test3() throws Exception { testGreeting("Cameron", 32); }
} 