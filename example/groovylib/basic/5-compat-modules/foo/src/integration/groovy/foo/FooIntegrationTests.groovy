package foo

import org.junit.jupiter.api.Test

class FooIntegrationTests {
    @Test
    void "hello should print correct greeting"() {
        // Groovy creates an implicit class for the script named after the file
        def foo = new Foo()
        assert foo.hello() == "Hello World, Earth"
    }
}
