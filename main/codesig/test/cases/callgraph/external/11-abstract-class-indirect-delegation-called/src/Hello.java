package hello;

// We implement an external interface, and never even call that method but
// instead pass it to some other third-party code (the `InputStreamReader`
// constructor) that calls that method.
//
// Make sure we treat this conservatively and mark all methods that are present
// on the type of the third-party code parameter as called
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

    public int uncalled(){
        return 1337;
    }
}

public class Hello{
    public static int main() throws java.io.IOException{
        java.io.BufferedReader is =
            new java.io.BufferedReader(new java.io.InputStreamReader(new Foo()));

        return is.readLine().length();
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
        "hello.Foo#<init>()void"
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
        "hello.Foo#readSpecial()int"
    ]
}
*/
