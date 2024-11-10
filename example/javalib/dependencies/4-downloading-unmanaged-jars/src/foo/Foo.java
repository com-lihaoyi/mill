package foo;

import com.williamfiset.fastjavaio.InputReader;
import java.io.FileInputStream;
import java.io.IOException;

public class Foo {

  public static void main(String[] args) {
    String filePath = args[0];
    InputReader fi = null;
    try {
      fi = new InputReader(new FileInputStream(filePath));
      String line;
      while ((line = fi.nextLine()) != null) {
        System.out.println(line);
      }
    } catch (IOException e) {
      e.printStackTrace();
    } finally {
      if (fi != null) {
        try {
          fi.close();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
  }
}
