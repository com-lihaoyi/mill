package hello;

// We implement an external interface, and never even call that method but
// instead pass it to some other third-party code that *also* does not call
// that method.
//
// Make sure we treat this conservatively and mark all methods that are present
// on the type of the third-party code parameter as called, because we don't
// analyze the call-graph of external code so we have to assume every method
// that is present on the type of the parameter we pass to the
// `java.io.OutputStreamWriter` constructor may get called
class Bar extends java.io.ByteArrayOutputStream{
    public synchronized void write(byte b[], int off, int len) {
        // do nothing
    }

    public int uncalled(){
        return 1337;
    }
}

public class Hello{
    public static int main() throws java.io.IOException{
        java.io.OutputStreamWriter os = new java.io.OutputStreamWriter(new Bar());

        return 1234;
    }
}

/* EXPECTED CALL GRAPH
{
    "hello.Hello.main()int": [
        "hello.Bar#<init>()void",
        "hello.Bar#write(byte[],int,int)void"
    ]
}
*/
