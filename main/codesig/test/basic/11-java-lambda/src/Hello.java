package hello;

public class Hello{
    public static int main(){
        java.util.function.IntSupplier foo = () -> used();
        return foo.getAsInt();
    }
    public static int used(){ return 2; }
    public static int unused(){ return 1; }
}

/* EXPECTED CALL GRAPH
{
    "hello.Hello.main()int": ["hello.Hello.used()int"]
}
*/
