package hello;

import java.util.function.IntSupplier;
import java.util.function.DoubleSupplier;

// When an external interface is implemented multiple times, only instantiated
// once, but we only make the virtual call through the interfacae. We cannot be
// sure we are only calling that specific implementation and not any of the
// other implementations, since we do not do dataflow analysis

class Foo implements IntSupplier{
    public int getAsInt(){ return 1; }
}

class Bar implements IntSupplier{
    public int getAsInt(){ return 1; }
}

public class Hello{
    public static int main(){
        IntSupplier is = new Foo();
        return bar(is);
    }
    public static int bar(IntSupplier is) {
        return is.getAsInt();
    }
}

/* EXPECTED CALL GRAPH
{
    "hello.Hello.bar(java.util.function.IntSupplier)int": [
        "hello.Bar#getAsInt()int",
        "hello.Foo#getAsInt()int"
    ],
    "hello.Hello.main()int": [
        "hello.Foo#<init>()void",
        "hello.Hello.bar(java.util.function.IntSupplier)int"
    ]
}
*/
