package hello;

import java.util.function.IntSupplier;
import java.util.function.DoubleSupplier;

// We instantiate this and call its method, so we record that in
// the call graph.
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

// `Foo#<init>` does not end up calling `IntSupplier#<init>` to
// `IntSupplier#read` and `Foo#read`, because `IntSupplier` is a Java
// `interface` and does not have a constructor
//
// `Foo#uncalled` we do not need to assume gets called by `IntSupplier#<init>`,
// as `uncalled` is a method on `Foo` and not `IntSupplier`, so `IntSupplier`
// would have no way to call it

/* expected-direct-call-graph
{
    "hello.Hello.bar(java.util.function.IntSupplier)int": [
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
    "hello.Hello.bar(java.util.function.IntSupplier)int": [
        "hello.Foo#getAsInt()int"
    ],
    "hello.Hello.main()int": [
        "hello.Foo#<init>()void",
        "hello.Foo#getAsInt()int",
        "hello.Hello.bar(java.util.function.IntSupplier)int"
    ]
}
*/
