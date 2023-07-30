package hello;

import java.util.function.IntSupplier;
import java.util.function.DoubleSupplier;

// We implement a method for an external interface we do not call anywhere.
//
// It should still appear as getting called by it's <init> method since it's a SAM
class Unused implements DoubleSupplier{
    public double getAsDouble(){ return 1.0; }
}

public class Hello{
    public static int main(){
        return 123;
    }
}

/* expected-direct-call-graph
{
    "hello.Unused#<init>()void": [
        "hello.Unused#getAsDouble()double"
    ]
}
*/

/* expected-transitive-call-graph
{
    "hello.Unused#<init>()void": [
        "hello.Unused#getAsDouble()double"
    ]
}
*/
