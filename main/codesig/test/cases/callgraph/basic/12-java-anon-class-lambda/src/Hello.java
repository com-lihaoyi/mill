package hello;

public class Hello{
    public static int main(){
        java.util.function.IntSupplier foo = new java.util.function.IntSupplier(){
            public int getAsInt(){ return used(); }
        };
        return foo.getAsInt();
    }
    public static int used(){ return 2; }
    public static int unused(){ return 1; }
}

// We treat single-abstract-method classes similarly to InvokeDynamic lambdas:
// we count them as calling their implementation method *when instantiated*,
// not when it actually gets called. This is because we cannot trust the
// compiler to reliably generate InvokeDynamic bytecodes for lambdas (e.g.
// https://github.com/scala/scala/pull/3616) and so need to be able to handle
// either code pattern

/* expected-direct-call-graph
{
    "hello.Hello.main()int": [
        "hello.Hello$1#<init>()void",
        "hello.Hello$1#getAsInt()int"
    ],
    "hello.Hello$1#getAsInt()int": [
        "hello.Hello.used()int"
    ]
}
*/

/* expected-transitive-call-graph
{
    "hello.Hello.main()int": ["hello.Hello.used()int"]
}
*/
