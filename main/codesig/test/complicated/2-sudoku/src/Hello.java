package hello;
// Taken from https://github.com/lihaoyi/Metascala/blob/76dfbfa18484b9ee39bd09453328ea1081fcab6b/src/test/java/metascala/full/Sudoku.java

import java.util.*;
import java.math.BigInteger;
import java.util.concurrent.atomic.*;
import java.util.regex.*;
import java.lang.reflect.Array;

public class Hello {
    public static String run(){
        int[][] grid = {
            {5, 3, 0, 0, 7, 0, 0, 0, 0},
            {6, 0, 0, 1, 9, 5, 0, 0, 0},
            {0, 9, 8, 0, 0, 0, 0, 6, 0},
            {8, 0, 0, 0, 6, 0, 0, 0, 3},
            {4, 0, 0, 8, 0, 3, 0, 0, 1},
            {7, 0, 0, 0, 2, 0, 0, 0, 6},
            {0, 6, 0, 0, 0, 0, 2, 8, 0},
            {0, 0, 0, 4, 1, 9, 0, 0, 5},
            {0, 0, 0, 0, 8, 0, 0, 7, 9},
        };
        Hello.solve(0, 0, grid);
        return Hello.writeMatrix(grid);
    }
    static boolean solve(int i, int j, int[][] cells) {
        if (i == 9) {
            i = 0;
            if (++j == 9)
                return true;
        }
        if (cells[i][j] != 0)  // skip filled cells
            return solve(i+1,j,cells);

        for (int val = 1; val <= 9; ++val) {
            if (legal(i,j,val,cells)) {
                cells[i][j] = val;
                if (solve(i+1,j,cells))
                    return true;
            }
        }
        cells[i][j] = 0; // reset on backtrack
        return false;
    }

    static boolean legal(int i, int j, int val, int[][] cells) {
        for (int k = 0; k < 9; ++k)  // row
            if (val == cells[k][j])
                return false;

        for (int k = 0; k < 9; ++k) // col
            if (val == cells[i][k])
                return false;

        int boxRowOffset = (i / 3)*3;
        int boxColOffset = (j / 3)*3;
        for (int k = 0; k < 3; ++k) // box
            for (int m = 0; m < 3; ++m)
                if (val == cells[boxRowOffset+k][boxColOffset+m])
                    return false;

        return true; // no violations, so it's legal
    }

    static String writeMatrix(int[][] solution) {

        StringBuilder s = new StringBuilder("\n");
        for (int i = 0; i < 9; ++i) {
            if (i % 3 == 0)
                s.append(" -----------------------\n");
            for (int j = 0; j < 9; ++j) {
                if (j % 3 == 0) s.append("| ");
                s.append(
                    solution[i][j] == 0
                        ? " "
                        : Integer.toString(solution[i][j])
                );

                s.append(' ');
            }
            s.append("|\n");
        }
        s.append(" ----------------------- ");
        return s.toString();
    }

}
/* EXPECTED TRANSITIVE
{
    "hello.Hello.run()java.lang.String": [
        "hello.Hello.solve(I,I,[[I)Z",
        "hello.Hello.writeMatrix([[I)java.lang.String"
    ],
    "hello.Hello.solve(I,I,[[I)Z": [
        "hello.Hello.legal(I,I,I,[[I)Z",
        "hello.Hello.solve(I,I,[[I)Z"
    ]
}
*/
