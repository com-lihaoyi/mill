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

/* EXPECTED DEPENDENCIES
{
    "hello.Hello.main()int": [
        "hello.Bar#<init>()void",
        "hello.Bar.initBar()void",
        "hello.Foo.foo()int",
        "hello.Foo.initFoo()void",
        "hello.Qux.initQux()void"
    ]
}
*/
