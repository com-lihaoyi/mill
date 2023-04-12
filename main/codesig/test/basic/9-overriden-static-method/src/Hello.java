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
/* EXPECTED TRANSITIVE
{
    "hello.Hello#<init>()V": [
        "hello.Parent#<init>()V"
    ],
    "hello.Hello.main()I": [
        "hello.Hello.foo()I",
        "hello.Parent.bar()I"
    ],
    "hello.Parent#<init>()V": [
        "hello.GrandParent#<init>()V"
    ]
}
*/
