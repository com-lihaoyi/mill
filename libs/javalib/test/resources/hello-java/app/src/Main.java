package hello;

public class Main{
    public static String getMessage(String[] args){
        return Core.msg() + " " + args[0];
    }
    public static void main(String[] args){
        System.out.println(getMessage(args));
    }
}