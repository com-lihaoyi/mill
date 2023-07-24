package hello;

abstract class GrandParent{
    public int foo(){ return 1; }
    public int bar(){ return 2; }
}
abstract class Parent extends GrandParent{
    public int foo(){ return 3; }
    public int bar(){ return 4; }
}
public class Hello extends Parent{
    public static int main(){ return new Hello().foo() + new Hello().bar(); }

    public int foo(){ return 5; }
}
/* expected-direct-call-graph
{
    "hello.Hello#<init>()void": [
        "hello.Parent#<init>()void"
    ],
    "hello.Hello#bar()int": [
        "hello.Parent#bar()int"
    ],
    "hello.Hello.main()int": [
        "hello.Hello#<init>()void",
        "hello.Hello#bar()int",
        "hello.Hello#foo()int"
    ],
    "hello.Parent#<init>()void": [
        "hello.GrandParent#<init>()void"
    ]
}
*/

/* expected-transitive-call-graph
{
    "hello.Hello#<init>()void": [
        "hello.GrandParent#<init>()void",
        "hello.Parent#<init>()void"
    ],
    "hello.Hello#bar()int": [
        "hello.Parent#bar()int"
    ],
    "hello.Hello.main()int": [
        "hello.GrandParent#<init>()void",
        "hello.Hello#<init>()void",
        "hello.Hello#bar()int",
        "hello.Hello#foo()int",
        "hello.Parent#<init>()void",
        "hello.Parent#bar()int"
    ],
    "hello.Parent#<init>()void": [
        "hello.GrandParent#<init>()void"
    ]
}
*/
