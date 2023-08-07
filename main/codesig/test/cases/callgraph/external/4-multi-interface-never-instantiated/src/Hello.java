package hello;

import java.util.function.IntSupplier;
import java.util.function.DoubleSupplier;

// Simple case: we instantiate this and call its method, so we record that in
// the call graph.
class Foo implements java.util.Enumeration<Integer>{
    public boolean hasMoreElements() { return false; }
    public Integer nextElement() { return null; }
}

// We do not instantiate this, but it's a type we do call the method
// on in our code.
//
// Because we're doing a relatively conservative analysis, we still treat it as
// a possible dispatch target for `IntSupplier#getAsInt` and also record it in
// the call graph
class Bar extends Foo {
    public boolean hasMoreElements() { return true; }
    public Integer nextElement() { return 1; }
}


public class Hello{
    public static int main(){
        java.util.Enumeration<Integer> is = new Foo();
        return is.nextElement();
    }
}

/* expected-direct-call-graph
{
    "hello.Bar#<init>()void": [
        "hello.Foo#<init>()void"
    ],
    "hello.Bar#nextElement()java.lang.Object": [
        "hello.Bar#nextElement()java.lang.Integer"
    ],
    "hello.Foo#nextElement()java.lang.Object": [
        "hello.Bar#nextElement()java.lang.Integer",
        "hello.Foo#nextElement()java.lang.Integer"
    ],
    "hello.Hello.main()int": [
        "hello.Bar#hasMoreElements()boolean",
        "hello.Bar#nextElement()java.lang.Object",
        "hello.Foo#<init>()void",
        "hello.Foo#hasMoreElements()boolean",
        "hello.Foo#nextElement()java.lang.Object"
    ]
}
*/

/* expected-transitive-call-graph
{
    "hello.Bar#<init>()void": [
        "hello.Foo#<init>()void"
    ],
    "hello.Bar#nextElement()java.lang.Object": [
        "hello.Bar#nextElement()java.lang.Integer"
    ],
    "hello.Foo#nextElement()java.lang.Object": [
        "hello.Bar#nextElement()java.lang.Integer",
        "hello.Foo#nextElement()java.lang.Integer"
    ],
    "hello.Hello.main()int": [
        "hello.Bar#hasMoreElements()boolean",
        "hello.Bar#nextElement()java.lang.Integer",
        "hello.Bar#nextElement()java.lang.Object",
        "hello.Foo#<init>()void",
        "hello.Foo#hasMoreElements()boolean",
        "hello.Foo#nextElement()java.lang.Integer",
        "hello.Foo#nextElement()java.lang.Object"
    ]
}
*/
