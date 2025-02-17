package com.sumlib;

public class Sum {
    public static int apply(int[] numbers) {

        int sum = 0;
        for (int i = 0; i < numbers.length; i++) {
            sum += numbers[i];
        }
        return sum;

    }

}
