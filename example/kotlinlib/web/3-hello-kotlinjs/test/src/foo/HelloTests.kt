package foo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class HelloTests :
    FunSpec({
        test("hello") {
            val result = hello()
            result shouldBe "<h1>Hello World Wrong</h1>"
        }
    })
