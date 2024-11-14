package qux

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.string.shouldEndWith

class QuxTests : FunSpec({

    test("hello") {
        val result = Qux.hello()
        result shouldStartWith "Hello"
    }

    test("world") {
        val result = Qux.hello()
        result shouldEndWith "World"
    }
})
