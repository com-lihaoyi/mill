package hello;

class Parent{
    public int used(){ return 2; }
}
public class Hello extends Parent{
    public static int main(){ return new Hello().used(); }

    public int unused(){return 1;}
}
/* EXPECTED CALL GRAPH
{
    "hello.Hello#<init>()void": [
        "hello.Parent#<init>()void"
    ],
    "hello.Hello#used()int": [
        "hello.Parent#used()int"
    ],
    "hello.Hello.main()int": [
        "hello.Hello#<init>()void",
        "hello.Hello#used()int"
    ]
}
*/
