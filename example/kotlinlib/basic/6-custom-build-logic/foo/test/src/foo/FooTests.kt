package foo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FooTests :
    FunSpec({

        test("testSimple") {
            val expectedLineCount = 12
            val actualLineCount = getLineCount()?.trim().let { Integer.parseInt(it) }
            actualLineCount shouldBe expectedLineCount
        }
    })
