package hello;

import java.util.function.IntSupplier;
import java.util.function.DoubleSupplier;

// When an external interface is implemented multiple times, but when we call
// its method we call it on a specific subclass, we can be sure we are only
// calling that specific implementation and not any of the other
// implementations for the interface

class Foo implements java.util.Enumeration<Integer>{
    public boolean hasMoreElements() {return false;}
    public Integer nextElement() {return null;}
}

class Bar implements java.util.Enumeration<Integer>{
    public boolean hasMoreElements() {return true;}
    public Integer nextElement() {return 123;}
}


public class Hello{
    public static int main(){
        Foo is = new Foo();
        return bar(is);
    }
    public static int bar(Foo is) {
        return is.nextElement();
    }
}

/* expected-direct-call-graph
{
    "hello.Bar#nextElement()java.lang.Object": [
        "hello.Bar#nextElement()java.lang.Integer"
    ],
    "hello.Foo#nextElement()java.lang.Object": [
        "hello.Foo#nextElement()java.lang.Integer"
    ],
    "hello.Hello.bar(hello.Foo)int": [
        "hello.Foo#nextElement()java.lang.Integer"
    ],
    "hello.Hello.main()int": [
        "hello.Foo#<init>()void",
        "hello.Hello.bar(hello.Foo)int"
    ]
}
*/

/* expected-transitive-call-graph
{
    "hello.Bar#nextElement()java.lang.Object": [
        "hello.Bar#nextElement()java.lang.Integer"
    ],
    "hello.Foo#nextElement()java.lang.Object": [
        "hello.Foo#nextElement()java.lang.Integer"
    ],
    "hello.Hello.bar(hello.Foo)int": [
        "hello.Foo#nextElement()java.lang.Integer"
    ],
    "hello.Hello.main()int": [
        "hello.Foo#<init>()void",
        "hello.Foo#nextElement()java.lang.Integer",
        "hello.Hello.bar(hello.Foo)int"
    ]
}
*/
