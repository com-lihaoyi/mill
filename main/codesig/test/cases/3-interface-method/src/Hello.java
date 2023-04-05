package hello;

interface Foo{
    public int used();
}

class Bar implements Foo{
    public int used() { return 1; }
    public int unused1() { return 3; }
}
class Qux implements Foo{
    public int used() { return 2; }
    public int unused2() { return 4; }
}
public class Hello{
    public static int main(){
        return new Bar().used() + new Qux().used();
    }
}

/* EXPECTED TRANSITIVE
{
    "hello.Hello.main()I": [
        "hello.Bar#<init>()V",
        "hello.Bar#used()I",
        "hello.Qux#<init>()V",
        "hello.Qux#used()I"
    ]
}
*/
