package hello;

import com.google.gson.Gson;
import org.apache.commons.io.IOUtils;

public class Main {
  public static void main(String[] args) {
    Gson gson = new Gson();
    System.out.println("Gson class: " + gson.getClass().getName());
    System.out.println("IOUtils class: " + IOUtils.class.getName());
  }
}
