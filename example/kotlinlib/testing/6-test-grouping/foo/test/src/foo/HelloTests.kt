package foo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldStartWith

class HelloTests :
    FunSpec({
        test("hello") {
            println("Testing Hello")
            val result = Foo().hello()
            result shouldStartWith "Hello"
            java.lang.Thread.sleep(1000)
            println("Testing Hello Completed")
        }
    })
