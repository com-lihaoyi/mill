package hello;

import java.util.function.IntSupplier;
import java.util.function.DoubleSupplier;

class Foo implements IntSupplier{
    public int getAsInt(){ return 1; }
}

class Bar implements IntSupplier{
    public int getAsInt(){ return 1; }
}


public class Hello{
    public static int main(){
        IntSupplier is = new Foo();
        return is.getAsInt();
    }
}

// Because `Foo` and `Bar` are SAM implementations, we treat them as being
// called from their `<init>` methods

/* expected-direct-call-graph
{
    "hello.Bar#<init>()void": [
        "hello.Bar#getAsInt()int"
    ],
    "hello.Foo#<init>()void": [
        "hello.Foo#getAsInt()int"
    ],
    "hello.Hello.main()int": [
        "hello.Foo#<init>()void"
    ]
}
*/

/* expected-transitive-call-graph
{
    "hello.Bar#<init>()void": [
        "hello.Bar#getAsInt()int"
    ],
    "hello.Foo#<init>()void": [
        "hello.Foo#getAsInt()int"
    ],
    "hello.Hello.main()int": [
        "hello.Foo#<init>()void",
        "hello.Foo#getAsInt()int"
    ]
}
*/
