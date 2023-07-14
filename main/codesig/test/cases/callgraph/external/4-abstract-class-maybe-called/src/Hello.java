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

// Note that we mark `Bar#read` as called from `Bar#<init>`. This is because
// `Bar#<init>` calls `InputStream#<init>`, which is external and could
// potentially call `InputStream#read` and thus `Bar#read`. Because we do not
// analyze the callgraph of external classes, we do a conservative estimate and
// assume it does make such a call.

/* expected-direct-call-graph
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
    "hello.Hello.bar(java.io.InputStream)int": [
        "hello.Bar#called()int",
        "hello.Bar#read()int"
    ],
    "hello.Hello.main()int": [
        "hello.Bar#<init>()void",
        "hello.Bar#called()int",
        "hello.Bar#read()int",
        "hello.Hello.bar(java.io.InputStream)int"
    ]
}
*/
