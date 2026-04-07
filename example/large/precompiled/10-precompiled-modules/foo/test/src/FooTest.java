package foo;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import org.junit.jupiter.api.Test;

public class FooTest {
    /** Shared test utility that can be used by downstream test modules */
    public static void assertGreeting(String name, String expected) {
        assertThat(Foo.greet(name), equalTo(expected));
    }

    @Test
    public void testGreet() {
        assertGreeting("Foo", "Hello, Foo!");
    }
}
