package foo;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import java.util.Map;

public class Foo {

  public static void main(String[] args) throws Exception {
    String jsonString = args[0];
    JsonObject jsonObj = JsonParser.object().from(jsonString);

    for (Map.Entry<String, Object> entry : jsonObj.entrySet()) {
      System.out.println("Key: " + entry.getKey() + ", Value: " + entry.getValue());
    }
  }
}
