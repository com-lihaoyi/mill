package hello;
public class Hello{

    @mill.codesig.ExpectedDeps({"hello.Hello.used()I"})
    public static void main(String[] args){
        System.out.println(used());
    }

    @mill.codesig.ExpectedDeps
    public static int unused(){
        return 1;
    }

    @mill.codesig.ExpectedDeps
    public static int used(){
        return 2;
    }
}
