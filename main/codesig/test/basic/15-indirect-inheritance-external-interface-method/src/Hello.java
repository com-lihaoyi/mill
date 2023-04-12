package hello;

// We implement an external interface, but end up calling a method on another
// external interface that is the parent of the first.
//
// Make sure we can resolve that and still record in the call graph that the
// method is called
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
        return is.read();
    }
}

/* EXPECTED CALL GRAPH
{
    "hello.Foo#read()int": [
        "hello.Foo#readSpecial()int"
    ],
    "hello.Hello.main()int": [
        "hello.Foo#<init>()void",
        "hello.Foo#read()int"
    ]
}
*/
