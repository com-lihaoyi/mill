package hello;

interface Parent{
    default int used(){ return 2; }
}
public class Hello implements Parent{
    public static int main(){ return new Hello().used(); }

    public int unused(){return 1;}
}
/* EXPECTED TRANSITIVE
{
    "hello.Hello.main()I": [
        "hello.Hello#<init>()V",
        "hello.Parent#used()I"
    ]
}
*/
