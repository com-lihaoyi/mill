package hello;
import java.util.function.IntSupplier;
import java.util.function.DoubleSupplier;

// Simple case: we instantiate this and call its method, so we record that in
// the call graph.
class Foo implements IntSupplier{
    public int getAsInt(){ return 1; }
}

// We do not instantiate this, but it's a type we do call the method
// on in our code.
//
// Because we're doing a relatively conservative analysis, we still treat it as
// a possible dispatch target for `IntSupplier#getAsInt` and also record it in
// the call graph
class Bar implements IntSupplier{
    public int getAsInt(){ return 1; }
}


public class Hello{
    public static int main(){
        IntSupplier is = new Foo();
        return is.getAsInt();
    }
}

/* EXPECTED TRANSITIVE
{
    "hello.Hello.main()I": [
        "hello.Foo#<init>()V",
        "hello.Foo#getAsInt()I",
        "hello.Bar#getAsInt()I"
    ]
}
*/
