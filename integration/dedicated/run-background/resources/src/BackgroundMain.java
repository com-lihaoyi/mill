package test;

public class BackgroundMain{
    public static void main(String[] args) throws Exception{
        while(true){
            Thread.sleep(50);
            System.out.println("runBackground out logs");
            System.err.println("runBackground err logs");

        }
    }
}
    
