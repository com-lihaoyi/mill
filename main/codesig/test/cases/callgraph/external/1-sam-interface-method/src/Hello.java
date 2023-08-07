package hello;

import java.util.function.IntSupplier;
import java.util.function.DoubleSupplier;

class Foo implements IntSupplier{
    public int getAsInt(){ return 1; }
    public int uncalled(){ return 2; }
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

// Since `Foo` is a SAM implementation, we treat its SAM method `getAsInt`
// as being called when it is instantiated

/* expected-direct-call-graph
{
    "hello.Foo#<init>()void": [
        "hello.Foo#getAsInt()int"
    ],
    "hello.Hello.main()int": [
        "hello.Foo#<init>()void",
        "hello.Hello.bar(java.util.function.IntSupplier)int"
    ]
}
*/

/* expected-transitive-call-graph
{
    "hello.Foo#<init>()void": [
        "hello.Foo#getAsInt()int"
    ],
    "hello.Hello.main()int": [
        "hello.Foo#<init>()void",
        "hello.Foo#getAsInt()int",
        "hello.Hello.bar(java.util.function.IntSupplier)int"
    ]
}
*/

