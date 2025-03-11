package foo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldEndWith

class WorldTests :
    FunSpec({
        test("world") {
            println("Testing World")
            val result = Foo().hello()
            result shouldEndWith "World"
            java.lang.Thread.sleep(1000)
            println("Testing World Completed")
        }
    })
