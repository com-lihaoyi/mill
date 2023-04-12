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
        Foo bar = new Bar();
        Foo qux = new Qux();
        return bar.used() + qux.used();
    }
}

/* EXPECTED TRANSITIVE
{
    "hello.Hello.main()int": [
        "hello.Foo#used()int",
        "hello.Bar#<init>()void",
        "hello.Bar#used()int",
        "hello.Qux#<init>()void",
        "hello.Qux#used()int"
    ]
}
*/
