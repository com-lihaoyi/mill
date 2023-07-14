package hello;

public class Hello{
    public static int main(){ return new Hello().used(); }
    public int used(){ return 2; }
    public int unused(){ return 1; }
}

/* expected-direct-call-graph
{
    "hello.Hello.main()int": ["hello.Hello#<init>()void", "hello.Hello#used()int"]
}
*/

/* expected-transitive-call-graph
{
    "hello.Hello.main()int": ["hello.Hello#<init>()void", "hello.Hello#used()int"]
}
*/
