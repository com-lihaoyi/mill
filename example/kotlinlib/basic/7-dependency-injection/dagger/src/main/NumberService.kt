package com.example.dagger

import javax.inject.Inject

class NumberService @Inject constructor(private val numberGenerator: NumberGenerator) {
    fun generateNumber(): Int = numberGenerator.generate()

    fun generateNNumbers(n: Int): List<Int> = (1..n).map { numberGenerator.generate() }
}
