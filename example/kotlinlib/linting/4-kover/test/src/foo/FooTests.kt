package foo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FooTests : FunSpec({
    test("kotlin - success") {
        action(true, true) shouldBe "one, two"
    }
})
