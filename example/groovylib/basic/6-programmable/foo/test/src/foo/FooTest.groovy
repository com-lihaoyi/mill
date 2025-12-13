package foo

import org.junit.jupiter.api.Test

class FooTest {

    @Test
    void "generateHtml should print correct html"() {
        def foo = new Foo()
        assert foo.generateHtml("Hello World") == "<h1>Hello World</h1>"
    }
}