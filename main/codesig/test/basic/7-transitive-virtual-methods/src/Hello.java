package hello;

public class Hello{
    public static int main(){
        return new Hello().used();
    }

    public int unused(){ return usedTransitive(); }

    public int used(){ return usedTransitive(); }

    public int usedTransitive(){ return 2 + Hello.usedDeepestStatic(); }

    public static int usedDeepestStatic(){ return 3; }
}

/* EXPECTED CALL GRAPH
{
    "hello.Hello#unused()int": [
        "hello.Hello#usedTransitive()int"
    ],
    "hello.Hello#used()int": [
        "hello.Hello#usedTransitive()int"
    ],
    "hello.Hello#usedTransitive()int": [
        "hello.Hello.usedDeepestStatic()int"
    ],
    "hello.Hello.main()int": [
        "hello.Hello#<init>()void",
        "hello.Hello#used()int"
    ]
}
*/
