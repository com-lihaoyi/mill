package hello;

interface Parent{
    default int used(){ return 2; }
}
public class Hello implements Parent{
    public static int main(){ return new Hello().used(); }

    public int unused(){return 1;}
}

/* expected-direct-call-graph
{
    "hello.Hello.main()int": [
        "hello.Hello#<init>()void",
        "hello.Parent#used()int"
    ]
}
*/

/* expected-transitive-call-graph
{
    "hello.Hello.main()int": [
        "hello.Hello#<init>()void",
        "hello.Parent#used()int"
    ]
}
*/
