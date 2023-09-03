package hello;

public class Hello{
    public static void main(String[] args){
        switch(args.length){
            case 0: System.out.println(0); break;
            case 1: System.out.println(1); break;
            case 3: System.out.println(2); break;
        }
    }
}
