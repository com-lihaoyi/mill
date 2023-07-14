package hello;

// Make sure that we create external call graph edges when calling external
// static methods too.
class Foo {
    public String toString() { return "Foo"; }
}

public class Hello{
    public static void main(){
        Foo foo = new Foo();
        bar(foo);
    }
    public static void bar(Foo foo) {
        System.identityHashCode(foo);
    }
}

// In this case, `bar` calling `System.identityHashCode(Object)` should be
// treated as calling all methods of `Object` on all sub-classes of `Object`,
// which includes `Foo#toString`. This is in addition to everyone's `<init>`
// methods also calling `Foo#toString` via `Object#<init>`.

/* expected-direct-call-graph
{
    "hello.Foo#<init>()void": [
        "hello.Foo#toString()java.lang.String"
    ],
    "hello.Hello#<init>()void": [
        "hello.Foo#toString()java.lang.String"
    ],
    "hello.Hello.bar(hello.Foo)void": [
        "hello.Foo#toString()java.lang.String"
    ],
    "hello.Hello.main()void": [
        "hello.Foo#<init>()void",
        "hello.Hello.bar(hello.Foo)void"
    ]
}
*/

/* expected-transitive-call-graph
{
    "hello.Foo#<init>()void": [
        "hello.Foo#toString()java.lang.String"
    ],
    "hello.Hello#<init>()void": [
        "hello.Foo#toString()java.lang.String"
    ],
    "hello.Hello.bar(hello.Foo)void": [
        "hello.Foo#toString()java.lang.String"
    ],
    "hello.Hello.main()void": [
        "hello.Foo#<init>()void",
        "hello.Hello.bar(hello.Foo)void",
        "hello.Foo#toString()java.lang.String"
    ]
}
*/
