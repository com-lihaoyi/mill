package com.sumlib;



import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
public class SumUnitTest {
    @Test
    public void sum_isCorrect() {
        assertEquals(2, Main.sum(1,1));
    }
}
