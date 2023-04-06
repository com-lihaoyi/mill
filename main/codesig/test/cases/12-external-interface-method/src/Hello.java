package hello;
import java.util.function.IntSupplier;
class Foo implements IntSupplier{
    public int getAsInt(){ return 1; }
}
public class Hello{
    public static int main(){
        IntSupplier is = new Foo();
        return is.getAsInt();
    }
}

/* EXPECTED TRANSITIVE
{
    "hello.Hello.main()I": [
        "hello.Foo#<init>()V",
        "hello.Foo#getAsInt()I"
    ]
}
*/
