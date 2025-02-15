package hello.tests

import hello.getHelloString
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FooTest :
    FunSpec({
        test("testSuccess") {
            getHelloString() shouldBe "Hello, world!"
        }

        test("testFailure") {
            getHelloString() shouldBe "WRONG!"
        }
    })
