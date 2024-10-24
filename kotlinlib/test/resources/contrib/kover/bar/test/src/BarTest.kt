package bar

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BarTest : FunSpec({
    test("kotlin - success") {
        action(true, true) shouldBe "one, two"
    }

    test("java - success") {
        BarJava.action(true, true) shouldBe "one, two"
    }
})
