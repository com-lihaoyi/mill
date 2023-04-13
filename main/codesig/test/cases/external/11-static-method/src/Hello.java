package hello;

// We instantiate this and call its method, so we record that in
// the call graph.
class Foo {
    public String toString() { return "Foo"; }
}

public class Hello{
    public static void main(){
        Foo foo = new Foo();
        bar(foo);
    }
    public static void bar(Foo foo) {
        System.out.println(foo);
    }
}

// `Foo#<init>` does not end up calling `IntSupplier#<init>` to
// `IntSupplier#read` and `Foo#read`, because `IntSupplier` is a Java
// `interface` and does not have a constructor
//
// `Foo#uncalled` we do not need to assume gets called by `IntSupplier#<init>`,
// as `uncalled` is a method on `Foo` and not `IntSupplier`, so `IntSupplier`
// would have no way to call it

/* EXPECTED CALL GRAPH
{
    "hello.Hello.bar(java.util.function.IntSupplier)int": [
        "hello.Foo#getAsInt()int"
    ],
    "hello.Hello.main()int": [
        "hello.Foo#<init>()void",
        "hello.Hello.bar(java.util.function.IntSupplier)int"
    ]
}
*/
