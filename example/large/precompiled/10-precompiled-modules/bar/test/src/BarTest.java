package bar;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import org.junit.jupiter.api.Test;

import foo.FooTest;

public class BarTest {
    @Test
    public void testGreetAll() {
        assertThat(
            Bar.greetAll(new String[]{"Bar", "Qux"}),
            equalTo("Hello, Bar!, Hello, Qux!")
        );
    }

    @Test
    public void testFooGreetFromBarTest() {
        // Use the shared test utility from foo.test
        FooTest.assertGreeting("Bar", "Hello, Bar!");
    }
}
