package hello;

public class Hello{
    public static void main(String[] args){
        System.out.println(1);
        new Hello().doThing();
    }
    public void doThing(){
        System.out.println(2);
    }
}
