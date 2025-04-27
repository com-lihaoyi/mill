package blocks;

// https://checkstyle.org/checks/blocks/avoidnestedblocks.html#ArrayTrailingComma
public class ArrayTrailingComma {

    public class Example1 {
        int[] numbers = {1, 2, 3};
        boolean[] bools = {
            true,
            true,
            false  // violation
        };

        String[][] text = {{},{},};

        double[][] decimals = {
            {0.5, 2.3, 1.1,},
            {1.7, 1.9, 0.6},
            {0.8, 7.4, 6.5}  // violation
        };

        char[] chars = {'a', 'b', 'c'
        };

        String[] letters = {
            "a", "b", "c"};

        int[] a1 = new int[]{
            1,
            2
            ,
        };

        int[] a2 = new int[]{
            1,
            2
            ,};
    }
}
