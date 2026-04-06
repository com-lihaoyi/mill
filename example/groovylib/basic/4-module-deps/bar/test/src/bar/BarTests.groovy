package bar

import org.junit.jupiter.api.Test

class BarTest {
    @Test
    void "generate html created properly"() {
        assert Bar.generateHtml("hello") == "<h1>hello</h1>"
    }

    @Test
    void "generated html is properly escaped"() {
        assert Bar.generateHtml("<hello>") == "<h1>&lt;hello&gt;</h1>"
    }
}
