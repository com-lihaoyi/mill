package hello;

// We implement an external interface, but end up calling a method on another
// external interface that is the parent of the first, and does not include the
// method we implemented.
//
// In this case, we are calling `Object#toString`, which has no way
// of referencing `InputStream#read` since `read` is in a child-class and not
// defined on `Object`. That means we can be confident that `toString` does not
// call our own implementation `Foo#read`
//
class Foo extends java.io.ByteArrayInputStream{
    public Foo() throws java.io.IOException{
        super(new byte[]{});
    }

    public int read(){
        return readSpecial();
    }
    public int readSpecial(){
        return 1337;
    }
}
public class Hello{
    public static int main() throws java.io.IOException{
        java.io.InputStream is = new Foo();
        return bar(is);
    }
    public static int bar(java.io.InputStream is){
        return is.toString().length();
    }
}


/* expected-direct-call-graph
{
    "hello.Foo#<init>()void": [
        "hello.Foo#read()int"
    ],
    "hello.Foo#read()int": [
        "hello.Foo#readSpecial()int"
    ],
    "hello.Hello.main()int": [
        "hello.Foo#<init>()void",
        "hello.Hello.bar(java.io.InputStream)int"
    ]
}
*/

/* expected-transitive-call-graph
{
    "hello.Foo#<init>()void": [
        "hello.Foo#read()int",
        "hello.Foo#readSpecial()int"
    ],
    "hello.Foo#read()int": [
        "hello.Foo#readSpecial()int"
    ],
    "hello.Hello.main()int": [
        "hello.Foo#<init>()void",
        "hello.Foo#read()int",
        "hello.Foo#readSpecial()int",
        "hello.Hello.bar(java.io.InputStream)int"
    ]
}
*/
