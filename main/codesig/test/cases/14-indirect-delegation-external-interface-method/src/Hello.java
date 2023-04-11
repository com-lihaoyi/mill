package hello;
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
        java.io.BufferedReader is =
            new java.io.BufferedReader(new java.io.InputStreamReader(new Foo()));

        return is.readLine().length();
    }
}

/* EXPECTED TRANSITIVE
{
    "hello.Foo#read()I": [
        "hello.Foo#readSpecial()I"
    ],
    "hello.Hello.main()I": [
        "hello.Foo#<init>()V",
        "hello.Foo#read()I",
        "hello.Foo#readSpecial()I"
    ]
}
*/
