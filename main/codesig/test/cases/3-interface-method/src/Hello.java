package hello;

interface Foo{
    public int used();
}

class Bar implements Foo{
    @mill.codesig.ExpectedDeps
    public int used() {
        return 1;
    }

    @mill.codesig.ExpectedDeps
    public int unused1() {
        return 3;
    }
}
class Qux implements Foo{
    @mill.codesig.ExpectedDeps
    public int used() {
        return 2;
    }

    @mill.codesig.ExpectedDeps
    public int unused2() {
        return 4;
    }
}
public class Hello{

    @mill.codesig.ExpectedDeps({
        "hello.Bar#<init>()V",
        "hello.Bar#used()I",
        "hello.Qux#<init>()V",
        "hello.Qux#used()I"
    })
    public static void main(String[] args){
        System.out.println(new Bar().used());
        System.out.println(new Qux().used());
    }
}
