package hello;

// Test the case where we extend an external interface and implement a method,
// but call a *separate* method on the interface that might call the method we
// implemented, but because this is a SAM method we instead consider it called
// at `<init>` time and not during the unknown external call.

class Bar extends java.io.InputStream{
    public synchronized int read() {
        return called();
    }

    public int called(){
        return 1337;
    }

    public int uncalled(){
        return 1337;
    }
}

public class Hello{
    public static int main() throws java.io.IOException{
        java.io.InputStream is = new Bar();
        return bar(is);
    }
    public static int bar(java.io.InputStream is) throws java.io.IOException{
        is.read(new byte[10], 0, 10);
        return 1234;
    }
}

/* expected-direct-call-graph
{
    "hello.Bar#<init>()void": [
        "hello.Bar#read()int"
    ],
    "hello.Bar#read()int": [
        "hello.Bar#called()int"
    ],
    "hello.Hello.main()int": [
        "hello.Bar#<init>()void",
        "hello.Hello.bar(java.io.InputStream)int"
    ]
}
*/

/* expected-transitive-call-graph
{
    "hello.Bar#<init>()void": [
        "hello.Bar#called()int",
        "hello.Bar#read()int"
    ],
    "hello.Bar#read()int": [
        "hello.Bar#called()int"
    ],
    "hello.Hello.main()int": [
        "hello.Bar#<init>()void",
        "hello.Bar#called()int",
        "hello.Bar#read()int",
        "hello.Hello.bar(java.io.InputStream)int"
    ]
}
*/
