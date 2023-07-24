package hello;


// We instantiate this and call its method, so we record that in
// the call graph.
class Foo implements java.util.Enumeration<Integer>{
    public int uncalled(){ return 2; }
    public boolean hasMoreElements() {return false;}
    public Integer nextElement() {return null;}
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

// `Foo#<init>` does not end up calling `IntSupplier#<init>` to
// `IntSupplier#read` and `Foo#read`, because `IntSupplier` is a Java
// `interface` and does not have a constructor
//
// `Foo#uncalled` we do not need to assume gets called by `IntSupplier#<init>`,
// as `uncalled` is a method on `Foo` and not `IntSupplier`, so `IntSupplier`
// would have no way to call it

/* expected-direct-call-graph
{
    "hello.Foo#nextElement()java.lang.Object": [
        "hello.Foo#nextElement()java.lang.Integer"
    ],
    "hello.Hello.bar(java.util.Enumeration)int": [
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
    "hello.Foo#nextElement()java.lang.Object": [
        "hello.Foo#nextElement()java.lang.Integer"
    ],
    "hello.Hello.bar(java.util.Enumeration)int": [
        "hello.Foo#hasMoreElements()boolean",
        "hello.Foo#nextElement()java.lang.Object",
        "hello.Foo#nextElement()java.lang.Integer"
    ],
    "hello.Hello.main()int": [
        "hello.Foo#<init>()void",
        "hello.Hello.bar(java.util.Enumeration)int",
        "hello.Foo#hasMoreElements()boolean",
        "hello.Foo#nextElement()java.lang.Object",
        "hello.Foo#nextElement()java.lang.Integer"
    ]
}
*/
