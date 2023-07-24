package hello;

// Private methods cannot be inherited or overriden, and so when we calll
// `Hello#bar` -> `Parent#foo`, we can be confident we are calling `Parent#foo`
// specifically and not calling `Hello#foo` or `Grandparent#foo`

class Grandparent{
    private int foo(){ return 2; }
}
class Parent extends Grandparent{
    private int foo(){ return 2; }
    int bar(){ return foo(); }
}
public class Hello extends Parent{
    public static int main(){ return new Hello().bar(); }

    private int foo(){ return 3; }
}
/* expected-direct-call-graph
{
    "hello.Hello#<init>()void": [
        "hello.Parent#<init>()void"
    ],
    "hello.Hello.main()int": [
        "hello.Hello#<init>()void",
        "hello.Parent#bar()int"
    ],
    "hello.Parent#<init>()void": [
        "hello.Grandparent#<init>()void"
    ],
    "hello.Parent#bar()int": [
        "hello.Parent#foo()int"
    ]
}
*/

/* expected-transitive-call-graph
{
    "hello.Hello#<init>()void": [
        "hello.Grandparent#<init>()void",
        "hello.Parent#<init>()void"
    ],
    "hello.Hello.main()int": [
        "hello.Grandparent#<init>()void",
        "hello.Hello#<init>()void",
        "hello.Parent#<init>()void",
        "hello.Parent#bar()int",
        "hello.Parent#foo()int"
    ],
    "hello.Parent#<init>()void": [
        "hello.Grandparent#<init>()void"
    ],
    "hello.Parent#bar()int": [
        "hello.Parent#foo()int"
    ]
}
*/
