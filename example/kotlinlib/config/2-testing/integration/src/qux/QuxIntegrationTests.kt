package qux

import io.kotest.core.spec.style.FunSpec

class QuxIntegrationTests :
    FunSpec({

        test("helloworld") {
            val result = Qux.hello()
            assertHello(result)
            assertWorld(result)
        }
    })
