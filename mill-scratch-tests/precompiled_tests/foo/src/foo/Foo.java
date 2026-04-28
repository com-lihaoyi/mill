package foo;

public class Foo {
    private final String name;

    public Foo(String name) {
        this.name = name;
    }

    public String greet() {
        return "Hello from " + name + "!";
    }

    public static void main(String[] args) {
        System.out.println(new Foo("Foo").greet());
    }
}
