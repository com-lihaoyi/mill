package hello.spock

import spock.lang.Specification
import static hello.Hello.*

class SpockTest extends Specification {

    def "test succeeds"() {
        expect:
        getHelloString() == "Hello, world!"
    }

    def "test fails"() {
        expect:
        getHelloString() == "Wrong"
    }

    def "sayHello to '#name' equals '#expected'"() {
        expect:
        sayHello(name) == expected

        where:
        name << ["Foo", "Bar"]
        expected = "Hello, $name"
    }
}
