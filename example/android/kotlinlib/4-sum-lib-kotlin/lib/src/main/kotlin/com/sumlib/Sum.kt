package com.sumlib

object Sum {
    fun apply(numbers: Array<Int>): Int {
        var sum = 0
        for (number in numbers) {
            sum += number
        }
        return sum
    }
}