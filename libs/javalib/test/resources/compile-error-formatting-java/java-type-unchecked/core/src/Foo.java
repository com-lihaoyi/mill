package unchecked;

public class Foo {
    public static<T> T[] test() {
        var obj = new java.lang.Object();
        return (T[]) obj;
    }
}
