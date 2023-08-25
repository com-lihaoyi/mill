package hello;

import java.util.function.IntSupplier;
import java.util.function.DoubleSupplier;

// We implement a method for an external interface we do not call anywhere.
//
// This should not appear in the call graph
class Unused implements java.util.Enumeration<Integer>{
    public boolean hasMoreElements() {return false;}
    public Integer nextElement() {return null;}
}

public class Hello{
    public static int main(){
        return 123;
    }
}

/* expected-direct-call-graph
{
    "hello.Unused#nextElement()java.lang.Object": [
        "hello.Unused#nextElement()java.lang.Integer"
    ]
}

*/

/* expected-transitive-call-graph
{
    "hello.Unused#nextElement()java.lang.Object": [
        "hello.Unused#nextElement()java.lang.Integer"
    ]
}

*/
