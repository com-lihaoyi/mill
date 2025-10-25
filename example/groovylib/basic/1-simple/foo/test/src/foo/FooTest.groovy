package foo

import org.junit.jupiter.api.Test

class FooTest {
    @Test
    void "generate html created properly"() {
        assert Foo.generateHtml("hello") == "<h1>hello</h1>"
    }
    
    @Test
    void "generated html is properly escaped"() {
        assert Foo.generateHtml("<hello>") == "<h1>&lt;hello&gt;</h1>"
    }
}
