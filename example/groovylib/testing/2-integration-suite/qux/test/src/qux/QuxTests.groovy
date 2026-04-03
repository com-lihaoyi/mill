package qux

import org.junit.jupiter.api.Test

class QuxTests {

    @Test
    void "hello"() {
        def result = new Qux().hello()
        assert result == "Hello World"
    }

    @Test
    void "world"() {
        def result = new Qux().hello()
        assert result.endsWith("World")
    }
}