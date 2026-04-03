package qux

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith

fun assertHello(result: String) {
    result shouldStartWith "Hello"
}

fun assertWorld(result: String) {
    result shouldEndWith "World"
}

class QuxTests :
    FunSpec({

        test("hello") {
            val result = Qux.hello()
            assertHello(result)
        }

        test("world") {
            val result = Qux.hello()
            assertWorld(result)
        }
    })
