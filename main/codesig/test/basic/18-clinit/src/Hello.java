package hello;

class Foo{
    static {
        initFoo();
    }

    static void initFoo(){ System.out.println("Initializing class Foo"); }
    public static int foo(){ return 1; }
}
class Bar{
    static {
        initBar();
    }

    static void initBar(){ System.out.println("Initializing class Bar"); }
    public int x = 1;
}
class Qux{
    static {
        initQux();
    }

    static void initQux(){ System.out.println("Initializing class Qux"); }
    public static int x = 1;
}
public class Hello{
    public static int main(){
        return Foo.foo() + new Bar().x + Qux.x;
    }
}

/* EXPECTED TRANSITIVE
{
    "hello.Hello.main()I": [
        "hello.Bar#<init>()V",
        "hello.Bar.initBar()V",
        "hello.Foo.foo()I",
        "hello.Foo.initFoo()V",
        "hello.Qux.initQux()V"
    ]
}
*/
