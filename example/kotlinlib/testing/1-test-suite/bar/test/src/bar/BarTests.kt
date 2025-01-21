package bar

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class BarTests :
    FunSpec({

        test("hello") {
            val result = Bar().hello()
            result shouldStartWith "Hello"
        }

        test("world") {
            val result = Bar().hello()
            result shouldEndWith "World"
        }

        test("mockito") {
            val mockBar = mock<Bar>()

            whenever(mockBar.hello()) doReturn "Hello Mockito World"

            val result = mockBar.hello()

            result shouldBe "Hello Mockito World"
            verify(mockBar).hello()
        }
    })
