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

// Single-abstract method "SAM" interfaces and their implementing classes are
// treated the same as JVM lambdas: we consider their impl "called" when
// instantiated, rather than treating them as potential dispatch sites when
// they are called. Both approaches are conservative approximations, but
// counting instantiations rather than invocations tends to be more precise
// in practice

/* expected-direct-call-graph
{
    "hello.Bar#<init>()void": [
        "hello.Bar#used()int"
    ],
    "hello.Hello.main()int": [
        "hello.Bar#<init>()void",
        "hello.Qux#<init>()void"
    ],
    "hello.Qux#<init>()void": [
        "hello.Qux#used()int"
    ]
}
*/

/* expected-transitive-call-graph
{
    "hello.Bar#<init>()void": [
        "hello.Bar#used()int"
    ],
    "hello.Hello.main()int": [
        "hello.Bar#<init>()void",
        "hello.Qux#<init>()void",
        "hello.Bar#used()int",
        "hello.Qux#used()int"
    ],
    "hello.Qux#<init>()void": [
        "hello.Qux#used()int"
    ]
}
*/
