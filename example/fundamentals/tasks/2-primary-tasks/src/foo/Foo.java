package foo;
import java.io.*;

public class Foo{
    public static final int value = 31337;
    public static void main(String[] args) throws IOException{
        System.out.println("Foo.value: " + value );
        System.out.println("args: " + String.join(" ", args) );

        InputStream res = Foo.class.getResourceAsStream("/foo.txt");

        try (BufferedReader br = new BufferedReader(new InputStreamReader(res))) {
            System.out.println("foo.txt resource: " + br.readLine());
        }
    }
}
