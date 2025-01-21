package foo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FooTests :
    FunSpec({
        test("hello") {
            val result = hello()
            result shouldBe "Hello World, Earth"
        }
    })
