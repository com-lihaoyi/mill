package foo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class FooMoreTests :
    FunSpec({

        test("hello") {
            val result = Foo().hello()
            result shouldStartWith "Hello"
        }

        test("world") {
            val result = Foo().hello()
            result shouldEndWith "World"
        }

        test("mockito") {
            val mockFoo = mock<Foo>()

            whenever(mockFoo.hello()) doReturn "Hello Mockito World"

            val result = mockFoo.hello()

            result shouldBe "Hello Mockito World"
            verify(mockFoo).hello()
        }
    })
