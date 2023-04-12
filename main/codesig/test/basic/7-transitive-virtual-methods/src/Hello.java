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

/* EXPECTED TRANSITIVE
{
    "hello.Hello#unused()I": [
        "hello.Hello#usedTransitive()I"
    ],
    "hello.Hello#used()I": [
        "hello.Hello#usedTransitive()I"
    ],
    "hello.Hello#usedTransitive()I": [
        "hello.Hello.usedDeepestStatic()I"
    ],
    "hello.Hello.main()I": [
        "hello.Hello#<init>()V",
        "hello.Hello#used()I"
    ]
}
*/
