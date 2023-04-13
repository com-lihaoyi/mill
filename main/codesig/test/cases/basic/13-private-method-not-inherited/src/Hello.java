package hello;

// Private methods cannot be inherited or overriden, and so when we calll
// `Hello#bar` -> `Parent#foo`, we can be confident we are calling `Parent#foo`
// specifically and not calling `Hello#foo`

class Parent{
    private int foo(){ return 2; }
    int bar(){ return foo(); }
}
public class Hello extends Parent{
    public static int main(){ return new Hello().bar(); }

    private int foo(){ return 3; }
}
/* EXPECTED CALL GRAPH
{
    "hello.Hello#<init>()void": [
        "hello.Parent#<init>()void"
    ],
    "hello.Hello.main()int": [
        "hello.Hello#<init>()void",
        "hello.Parent#bar()int"
    ],
    "hello.Parent#bar()int": [
        "hello.Parent#foo()int"
    ]
}
*/
