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
/* EXPECTED TRANSITIVE
{
    "hello.Hello#<init>()V": [
        "hello.Parent#<init>()V"
    ],
    "hello.Hello#bar()I": [
        "hello.Parent#bar()I"
    ],
    "hello.Hello.main()I": [
        "hello.Hello#<init>()V",
        "hello.Hello#bar()I",
        "hello.Hello#foo()I"
    ],
    "hello.Parent#<init>()V": [
        "hello.GrandParent#<init>()V"
    ]
}
*/
