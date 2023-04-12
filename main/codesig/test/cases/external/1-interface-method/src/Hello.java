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

// Although `Foo#<init>` does not call `Foo#getAsInt`, it does call
// `IntSupplier#<init>`, which has the potential to call
// `IntSupplier#getAsInt` and thus `Foo#getAsInt`. As we do not analyze the
// call graphs of external libraries, we have to be conservative, which means
// assuming that `Foo#<init>` may end up indirectly calling `Foo#getAsInt`
//
// `Foo#uncalled` we do not need to assume gets called by `IntSupplier#<init>`,
// as `uncalled` is a method on `Foo` and not `IntSupplier`, so `IntSupplier`
// would have no way to call it

/* EXPECTED CALL GRAPH
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
