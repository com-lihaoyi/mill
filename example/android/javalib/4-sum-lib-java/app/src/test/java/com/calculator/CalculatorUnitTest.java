package com.calculator.app;

import static org.junit.Assert.*;

import com.calculator.app.R;
import org.junit.Test;
import com.calculator.app.Calculator;

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
public class CalculatorUnitTest {
    @Test
    public void textSize_isCorrect() {

        assertEquals(0x7f010000, R.color.text_green);
    }

    @Test
    public void testPlus() {
        int[] numbers = {1, 2};
        assertEquals(3, Calculator.plus(numbers));
    }
}
