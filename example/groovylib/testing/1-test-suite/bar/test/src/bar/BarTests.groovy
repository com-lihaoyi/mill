package bar

import org.junit.jupiter.api.Test
import groovy.mock.interceptor.MockFor

class BarTests{
    @Test
    void "hello"() {
        def result = new Bar().hello()
        assert result == "Hello World"
    }

    @Test
    void "world"() {
        def result = new Bar().hello()
        assert result.endsWith("World")
    }

    @Test
    void "using groovy mocks"() {
        def mockBar = new MockFor(Bar)
        mockBar.demand.hello { "Hello GroovyMock World" }

        mockBar.use{
            def result = new Bar().hello()
            assert result == "Hello GroovyMock World"
        }
    }
}
