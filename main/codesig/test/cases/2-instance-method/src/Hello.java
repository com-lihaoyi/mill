package hello;
public class Hello{

    @mill.codesig.ExpectedDeps({"hello.Hello#<init>()V", "hello.Hello#used()I"})
    public static void main(String[] args){
        System.out.println(new Hello().used());
    }

    @mill.codesig.ExpectedDeps()
    public int unused(){
        return 1;
    }

    @mill.codesig.ExpectedDeps()
    public int used(){
        return 2;
    }
}
