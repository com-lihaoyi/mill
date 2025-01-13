package hello.tests

import hello.getHelloString
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FooTest :
    FunSpec({
        test("testFailure") {
            getHelloString() shouldBe "Hello, world!"
        }

        test("testSuccess") {
            getHelloString() shouldBe "WRONG!"
        }
    })
