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
    "hello.Hello.main()int": [
        "hello.Bar#used()int",
        "hello.Foo#used()int",
        "hello.Qux#<init>()void"
    ],
    "hello.Qux#<init>()void": [
        "hello.Bar#<init>()void"
    ]
}
*/
