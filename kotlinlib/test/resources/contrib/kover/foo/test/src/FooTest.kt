package foo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FooTest : FunSpec({
    test("kotlin - success") {
        action(true, true) shouldBe "one, two"
    }

    test("java - success") {
        FooJava.action(true, true) shouldBe "one, two"
    }
})
