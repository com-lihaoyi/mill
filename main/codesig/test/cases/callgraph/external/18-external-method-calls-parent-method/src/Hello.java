package hello;

class Foo extends Parent{
    void called(){ System.out.println("called"); }
    public void doGrandThingAbstract() {
        called();
    }
}

public class Hello{
    public static void main(){
        new Foo().doParentThing();
    }
}
// that it could call either of `doGrandThing` or `doParentThing`

/* expected-direct-call-graph
{
    "hello.Foo#<init>()void": [
        "hello.Foo#doGrandThingAbstract()void"
    ],
    "hello.Foo#doGrandThingAbstract()void": [
        "hello.Foo#called()void"
    ],
    "hello.Hello.main()void": [
        "hello.Foo#<init>()void"
    ]
}
*/

/* expected-transitive-call-graph
{
    "hello.Foo#<init>()void": [
        "hello.Foo#doGrandThingAbstract()void",
        "hello.Foo#called()void"
    ],
    "hello.Foo#doGrandThingAbstract()void": [
        "hello.Foo#called()void"
    ],
    "hello.Hello.main()void": [
        "hello.Foo#<init>()void",
        "hello.Foo#doGrandThingAbstract()void",
        "hello.Foo#called()void"
    ]
}
*/
