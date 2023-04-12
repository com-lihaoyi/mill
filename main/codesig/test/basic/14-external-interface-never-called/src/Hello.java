package hello;

import java.util.function.IntSupplier;
import java.util.function.DoubleSupplier;

// We implement a method for an external interface we do not call anywhere.
//
// This should not appear in the call graph
class Unused implements DoubleSupplier{
    public double getAsDouble(){ return 1.0; }
}

public class Hello{
    public static int main(){
        return 123;
    }
}

/* EXPECTED CALL GRAPH
{
}
*/
