package foo;

import org.junit.Test;

public class RandomTestsJ extends RandomTestsUtils {
    @Test
    public void test1() throws Exception { testGreeting("Storm", 38); }
    @Test
    public void test2() throws Exception { testGreeting("True", 32); }
    @Test
    public void test3() throws Exception { testGreeting("Vale", 28); }
} 