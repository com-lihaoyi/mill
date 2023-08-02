package hello;

// Make sure that when we call external methods, we only generate conservative
// call graph edges to local methods that are defined on the external class
// that we called the external methd on.

class Foo implements Parent{
    public void doParentThing() { System.out.println("Running doThing"); }
    public void doGrandThing() { System.out.println("Running doGrandThing"); }
    public void otherNonSamMethod() { System.out.println("Running otherNonSamMethod"); }
    public void notDoneThing() { System.out.println("Running notDoneThing"); }
}

public class Hello{
    public static void main(){
        Foo foo = new Foo();
        bar(foo);
        qux(foo);
    }
    public static void bar(Foo foo) {
        foo.doGrandThingConcrete();
    }
    public static void qux(Foo foo) {
        foo.doParentThingConcrete();
    }
}

// In this case, `bar` calls `doGrandThingConcrete` which is defined externally
// on `Grandparent`, and so it only has a possibility of calling `doGrandThing`
// on `Foo`. `qux` on the other hand calls `doParentThingConcrete` which is
// defined externally on `Parent` which inherits from `Grandparent`, meaning
// that it could call either of `doGrandThing` or `doParentThing`

/* expected-direct-call-graph
{
    "hello.Hello.bar(hello.Foo)void": [
        "hello.Foo#doGrandThing()void",
        "hello.Foo#otherNonSamMethod()void"
    ],
    "hello.Hello.main()void": [
        "hello.Foo#<init>()void",
        "hello.Hello.bar(hello.Foo)void",
        "hello.Hello.qux(hello.Foo)void"
    ],
    "hello.Hello.qux(hello.Foo)void": [
        "hello.Foo#doGrandThing()void",
        "hello.Foo#doParentThing()void",
        "hello.Foo#otherNonSamMethod()void"
    ]
}
*/

/* expected-transitive-call-graph
{
    "hello.Hello.bar(hello.Foo)void": [
        "hello.Foo#doGrandThing()void",
        "hello.Foo#otherNonSamMethod()void"
    ],
    "hello.Hello.main()void": [
        "hello.Foo#<init>()void",
        "hello.Foo#doGrandThing()void",
        "hello.Foo#doParentThing()void",
        "hello.Foo#otherNonSamMethod()void",
        "hello.Hello.bar(hello.Foo)void",
        "hello.Hello.qux(hello.Foo)void"
    ],
    "hello.Hello.qux(hello.Foo)void": [
        "hello.Foo#doGrandThing()void",
        "hello.Foo#doParentThing()void",
        "hello.Foo#otherNonSamMethod()void"
    ]
}
*/
