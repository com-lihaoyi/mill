package hello;

public class Core{
    public static String msg(){
        String msg = "Hello World";
        System.out.println("Environment:");
        System.getenv().forEach((k,v) -> System.out.println(k + "=" + v));
        System.out.println("Core.msg() returning: " + msg);
        return msg;
    }
}