package hello;

/**
 * Very verbose Javadoc comment on top of class
 */
public class Hello{
    /**
     * less verbose Javadoc comment for single method
     */
    public static int main(){
        System.out.println(1);
        return used();
    }



    public static int used(){
        // Random line-comment inside
        return 2;
    }


    public static int unused(){
        return 1;
    }
}
