package qux

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class QuxIntegrationTests :
    FunSpec({

        test("helloworld") {
            val result = Qux.hello()
            result shouldBe "Hello World"
        }
    })
