package foo

import bar.getString
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class HelloTests :
    FunSpec({

        test("success") {
            getString() shouldBe "Hello, world"
        }

        test("failure") {
            getString() shouldBe "Not hello, world"
        }
    })
