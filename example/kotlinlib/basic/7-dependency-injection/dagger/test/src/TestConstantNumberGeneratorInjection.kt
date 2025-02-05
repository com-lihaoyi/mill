package com.example.dagger

import kotlin.test.Test
import kotlin.test.assertEquals

class TestConstantNumberGeneratorInjection {

    @Test
    fun testGeneratedNumberFromInjection() {
        val testApp = DaggerTestApp.create()
        assertEquals(testApp.numberService().generateNumber(), 42)
    }

    @Test
    fun testGeneratedNumbers() {
        val testApp = DaggerTestApp.create()
        assertEquals(testApp.numberService().generateNNumbers(5), (1..5).map { 42 })
    }
}
