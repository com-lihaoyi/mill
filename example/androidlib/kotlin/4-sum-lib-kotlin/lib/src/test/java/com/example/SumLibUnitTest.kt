package com.example

import org.junit.Assert.*
import org.junit.Test
/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class SumLibUnitTest {
    @Test
    fun kotlin_dir_text_size_is_correct() {
        val numbers = arrayOf(1, 1)
        assertEquals(2, Sum.apply(numbers))
    }
}
