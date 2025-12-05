package foo;

public class StringUtils {
    public static String reverse(String s) {
        return new StringBuilder(s).reverse().toString();
    }

    public static String toUpperCase(String s) {
        return s.toUpperCase();
    }
}
