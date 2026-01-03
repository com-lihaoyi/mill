package foo

import org.junit.jupiter.api.Test
import groovy.mock.interceptor.MockFor

class FooMoreTests{
    @Test
    void "hello"() {
        def result = new Foo().hello()
        assert result == "Hello World"
    }

    @Test
    void "world"() {
        def result = new Foo().hello()
        assert result.endsWith("World")
    }

    @Test
    void "using groovy mocks"() {
        def mockFoo = new MockFor(Foo)
        mockFoo.demand.hello { "Hello GroovyMock World" }

        mockFoo.use{
            def result = new Foo().hello()
            assert result == "Hello GroovyMock World"
        }
    }
}
