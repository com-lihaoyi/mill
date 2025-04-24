package foo;

import com.fasterxml.jackson.databind.ObjectMapper;

public class Foo {
  public static void main(String[] args) throws Exception {
    System.out.println("JSONified using Jackson: " + new ObjectMapper().writeValueAsString(args));
  }
}
