package hello;

import java.util.function.IntSupplier;
import java.util.function.DoubleSupplier;

// We instantiate this and call its method, so we record that in
// the call graph.
class Foo implements IntSupplier{
    public int getAsInt(){ return 1; }
}

public class Hello{
    public static int main(){
        IntSupplier is = new Foo();
        return is.getAsInt();
    }
}

/* EXPECTED DEPENDENCIES
{
    "hello.Hello.main()int": [
        "hello.Foo#<init>()void",
        "hello.Foo#getAsInt()int"
    ]
}
*/
