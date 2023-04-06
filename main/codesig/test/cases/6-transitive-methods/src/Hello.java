package hello;
public class Hello{
    public static int main(){
        return usedStatic() + new Hello().used();
    }

    public static int unusedStatic(){ return usedTransitiveStatic(); }

    public static int usedStatic(){ return usedTransitiveStatic(); }

    public static int usedTransitiveStatic(){ return 2 + Hello.usedDeepestStatic(); }

    public int unused(){ return usedTransitive(); }

    public int used(){ return usedTransitive(); }

    public int usedTransitive(){ return 2 + Hello.usedDeepestStatic(); }

    public static int usedDeepestStatic(){ return 3; }
}

/* EXPECTED TRANSITIVE
{
    "hello.Hello#unused()I": [
        "hello.Hello#usedTransitive()I",
        "hello.Hello.usedDeepestStatic()I"
    ],
    "hello.Hello#used()I": [
        "hello.Hello#usedTransitive()I",
        "hello.Hello.usedDeepestStatic()I"
    ],
    "hello.Hello#usedTransitive()I": [
        "hello.Hello.usedDeepestStatic()I"
    ],
    "hello.Hello.main()I": [
        "hello.Hello#<init>()V",
        "hello.Hello#used()I",
        "hello.Hello#usedTransitive()I",
        "hello.Hello.usedDeepestStatic()I",
        "hello.Hello.usedStatic()I",
        "hello.Hello.usedTransitiveStatic()I"
    ],
    "hello.Hello.unusedStatic()I": [
        "hello.Hello.usedDeepestStatic()I",
        "hello.Hello.usedTransitiveStatic()I"
    ],
    "hello.Hello.usedStatic()I": [
        "hello.Hello.usedDeepestStatic()I",
        "hello.Hello.usedTransitiveStatic()I"
    ],
    "hello.Hello.usedTransitiveStatic()I": [
        "hello.Hello.usedDeepestStatic()I"
    ]
}
*/
