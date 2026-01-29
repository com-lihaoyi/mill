package foo;

public class StringUtils {
    public String reverse(String s) {
        return new StringBuilder(s).reverse().toString();
    }

    public String toUpperCase(String s) {
        return s.toUpperCase();
    }
}
