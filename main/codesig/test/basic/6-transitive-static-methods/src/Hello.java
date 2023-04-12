package hello;

public class Hello{
    public static int main(){
        return usedStatic();
    }

    public static int unusedStatic(){ return usedTransitiveStatic(); }

    public static int usedStatic(){ return usedTransitiveStatic(); }

    public static int usedTransitiveStatic(){ return 2 + Hello.usedDeepestStatic(); }

    public static int usedDeepestStatic(){ return 3; }
}

/* EXPECTED TRANSITIVE
{
    "hello.Hello.main()int": [
        "hello.Hello.usedStatic()int"
    ],
    "hello.Hello.unusedStatic()int": [
        "hello.Hello.usedTransitiveStatic()int"
    ],
    "hello.Hello.usedStatic()int": [
        "hello.Hello.usedTransitiveStatic()int"
    ],
    "hello.Hello.usedTransitiveStatic()int": [
        "hello.Hello.usedDeepestStatic()int"
    ]
}
*/
