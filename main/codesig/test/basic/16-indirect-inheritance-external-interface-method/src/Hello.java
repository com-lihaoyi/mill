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


// Although `Foo#<init>` does not call `Foo#read`, it does call
// `ByteArrayInputStream#<init>`, which has the potential to call
// `ByteArrayInputStream#read` and thus `Foo#read`. As we do not analyze the
// call graphs of external libraries, we have to be conservative, which means
// assuming that `Foo#<init>` may end up indirectly calling `Foo#read`

/* EXPECTED CALL GRAPH
{
   "hello.Foo#<init>()void": [
        "hello.Foo#read()int"
    ],
    "hello.Foo#read()int": [
        "hello.Foo#readSpecial()int"
    ],
    "hello.Hello.main()int": [
        "hello.Foo#<init>()void",
        "hello.Foo#read()int"
    ]
}
*/
