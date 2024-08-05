package foo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class Foo {
  public static void main(String[] args) throws Exception{
    System.out.println("JSONified using Jackson: " + new ObjectMapper().writeValueAsString(args));
  }
}