package foo;

public class Foo {
    public static String greet(String name) {
        return "Hello, " + name + "!";
    }

    public static void main(String[] args) throws Exception {
        System.out.println(greet("Foo"));
    }
}
