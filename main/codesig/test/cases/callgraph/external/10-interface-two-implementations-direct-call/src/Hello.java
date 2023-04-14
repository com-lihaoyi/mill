package hello;

import java.util.function.IntSupplier;
import java.util.function.DoubleSupplier;

// When an external interface is implemented multiple times, but when we call
// its method we call it on a specific subclass, we can be sure we are only
// calling that specific implementation and not any of the other
// implementations for the interface

class Foo implements IntSupplier{
    public int getAsInt(){ return 1; }
}

class Bar implements IntSupplier{
    public int getAsInt(){ return 1; }
}

public class Hello{
    public static int main(){
        Foo is = new Foo();
        return bar(is);
    }
    public static int bar(Foo is) {
        return is.getAsInt();
    }
}

/* EXPECTED CALL GRAPH
{
    "hello.Hello.bar(hello.Foo)int": [
        "hello.Foo#getAsInt()int"
    ],
    "hello.Hello.main()int": [
        "hello.Foo#<init>()void",
        "hello.Hello.bar(hello.Foo)int"
    ]
}
*/
