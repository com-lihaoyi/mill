package foo;

public class FooJava {
    public static String action(boolean one, boolean two) {
        if (one) {
            if (two) {
                return "one, two";
            } else {
                return "one";
            }
        } else {
            if (two) {
                return "two";
            } else {
                return "nothing";
            }
        }
    }
}
