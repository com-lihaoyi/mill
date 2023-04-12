package hello;

// Test the case where we extend an external interface and implement a method,
// but call a *separate* method on the interface that might call the method we
// implemented. Because we do not analyze the call graph of external classes,
// we have to conservatively assume that the external method we called could
// have called any other method defined on the interface type, and so assume
// that our implementation gets called indirectly

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

/* EXPECTED CALL GRAPH
{
    "hello.Bar#<init>()void": [
        "hello.Bar#read()int"
    ],
    "hello.Bar#read()int": [
        "hello.Bar#called()int"
    ],
    "hello.Hello.bar(java.io.InputStream)int": [
        "hello.Bar#read()int"
    ],
    "hello.Hello.main()int": [
        "hello.Bar#<init>()void",
        "hello.Bar#read()int",
        "hello.Hello.bar(java.io.InputStream)int"
    ]
}
*/
