package hello;



// When an external interface is implemented multiple times, only instantiated
// once, but we only make the virtual call through the interfacae. We cannot be
// sure we are only calling that specific implementation and not any of the
// other implementations, since we do not do dataflow analysis

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
        java.util.Enumeration<Integer> is = new Foo();
        return bar(is);
    }
    public static int bar(java.util.Enumeration<Integer> is) {
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
    "hello.Hello.bar(java.util.Enumeration)int": [
        "hello.Bar#hasMoreElements()boolean",
        "hello.Bar#nextElement()java.lang.Object",
        "hello.Foo#hasMoreElements()boolean",
        "hello.Foo#nextElement()java.lang.Object"
    ],
    "hello.Hello.main()int": [
        "hello.Foo#<init>()void",
        "hello.Hello.bar(java.util.Enumeration)int"
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
    "hello.Hello.bar(java.util.Enumeration)int": [
        "hello.Bar#hasMoreElements()boolean",
        "hello.Bar#nextElement()java.lang.Integer",
        "hello.Bar#nextElement()java.lang.Object",
        "hello.Foo#hasMoreElements()boolean",
        "hello.Foo#nextElement()java.lang.Integer",
        "hello.Foo#nextElement()java.lang.Object"
    ],
    "hello.Hello.main()int": [
        "hello.Bar#hasMoreElements()boolean",
        "hello.Bar#nextElement()java.lang.Integer",
        "hello.Bar#nextElement()java.lang.Object",
        "hello.Foo#<init>()void",
        "hello.Foo#hasMoreElements()boolean",
        "hello.Foo#nextElement()java.lang.Integer",
        "hello.Foo#nextElement()java.lang.Object",
        "hello.Hello.bar(java.util.Enumeration)int"
    ]
}
*/
