package hello;

interface Foo{
    public int used();
}

class Bar{
    public int used(){ return 2; }
}

class Qux extends Bar implements Foo{}

public class Hello {
    public static int main(){
        Foo foo = new Qux();
        return foo.used();
    }
}
/* EXPECTED TRANSITIVE
{
    "hello.Hello.main()I": [
        "hello.Bar#used()I",
        "hello.Foo#used()I",
        "hello.Qux#<init>()V"
    ],
    "hello.Qux#<init>()V": [
        "hello.Bar#<init>()V"
    ]
}
*/
