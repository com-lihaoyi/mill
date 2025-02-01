package bar

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BarTests : FunSpec({
    test("kotlin - success") {
        action(true, true) shouldBe "one, two"
    }
})
