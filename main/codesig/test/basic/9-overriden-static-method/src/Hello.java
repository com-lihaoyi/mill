package hello;

class GrandParent{
    public static int foo(){ return 1; }
    public static int bar(){ return 2; }
}
class Parent extends GrandParent{
    public static int foo(){ return 3; }
    public static int bar(){ return 4; }
}
public class Hello extends Parent{
    public static int main(){ return foo() + bar(); }

    public static int foo(){ return 5; }
}
/* EXPECTED DEPENDENCIES
{
    "hello.Hello#<init>()void": [
        "hello.Parent#<init>()void"
    ],
    "hello.Hello.main()int": [
        "hello.Hello.foo()int",
        "hello.Parent.bar()int"
    ],
    "hello.Parent#<init>()void": [
        "hello.GrandParent#<init>()void"
    ]
}
*/
