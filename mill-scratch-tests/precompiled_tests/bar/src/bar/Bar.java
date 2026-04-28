package bar;

import foo.Foo;

public class Bar {
    public static String greetAll(String[] names) {
        StringBuilder sb = new StringBuilder();
        for (String name : names) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(new Foo(name).greet());
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        System.out.println(greetAll(new String[]{"Bar", "Baz"}));
    }
}
