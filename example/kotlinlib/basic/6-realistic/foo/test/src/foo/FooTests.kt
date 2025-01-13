package foo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FooTests :
    FunSpec({
        test("hello") {
            Foo.VALUE shouldBe "<h1>hello</h1>"
        }
    })
