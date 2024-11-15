package qux

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class QuxTest :
    FunSpec({
        test("kotlin - success") {
            action(true, true) shouldBe "one, two"
        }

        test("java - success") {
            QuxJava.action(true, true) shouldBe "one, two"
        }
    })
