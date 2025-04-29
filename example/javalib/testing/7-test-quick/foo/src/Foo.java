package foo;

public class Foo {
    public static void main(String[] args) {
        System.out.println(Bar.greet("World"));
    }

    public static String greet(String name) {
        return Bar.greet(name);
    }

    public static String greet2(String name) {
        return Bar.greet2(name);
    }
}