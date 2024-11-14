package hello.tests

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import hello.getHelloString

class FooTest :
    FunSpec({
        test("testFailure") {
            getHelloString() shouldBe "Hello, world!"
        }

        test("testSuccess") {
            getHelloString() shouldBe "WRONG!"
        }
    })
