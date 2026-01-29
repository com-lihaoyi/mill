package com.example.dagger

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TestConstantNumberGeneratorInjection :
    FunSpec({
        test("testGeneratedNumberFromInjection") {
            val testApp = DaggerTestApp.create()
            testApp.numberService().generateNumber() shouldBe 42
        }
        test("testGeneratedNumbers") {
            val testApp = DaggerTestApp.create()
            testApp.numberService().generateNNumbers(5) shouldBe (1..5).map { 42 }
        }
    })
