package foo;
import java.io.*;

public class Foo{
    public static void main(String[] args) throws IOException{
        InputStream res = Foo.class.getResourceAsStream("/snippet.txt");

        try (BufferedReader br = new BufferedReader(new InputStreamReader(res))) {
            System.out.println("generated snippet.txt resource: " + br.readLine());
        }
    }
}
