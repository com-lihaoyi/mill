package bar

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BarTests :
    FunSpec({

        test("world") {
            Bar.value() shouldBe "<p>world</p>"
        }
    })
