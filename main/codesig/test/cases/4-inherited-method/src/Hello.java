package hello;

class Parent{
    @mill.codesig.ExpectedDeps
    public int used(){
        return 2;
    }
}
public class Hello extends Parent{

    @mill.codesig.ExpectedDeps({
        "hello.Hello#<init>()V",
        "hello.Parent#<init>()V",
        "hello.Parent#used()I",
        "hello.Hello#used()I"
    })
    public static void main(String[] args){
        System.out.println(new Hello().used());
    }

    @mill.codesig.ExpectedDeps
    public int unused(){
        return 1;
    }
}
