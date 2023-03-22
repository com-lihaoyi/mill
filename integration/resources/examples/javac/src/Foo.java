package examples.javac.src;
public class Foo{
    static int value = 31337;
    public static void main(String[] args){
        System.out.println(value + Bar.value);
    }
}
