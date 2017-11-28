package test;
public class Baz{
    static int value = 73313;
    public static void main(String[] args){
        System.out.println(value + Bar.value + Foo.value);
    }
}
