package foo;

import java.io.IOException;
import java.util.Properties;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

public class Foo2 {
  public static final String value() {
    Context context = new Context();
    context.setVariable("text", "hello2");
    return new TemplateEngine().process("<h1 th:text=\"${text}\"></h1>", context);
  }

  public static void main(String[] args) {
    System.out.println("Foo2.value: " + Foo2.value());
    System.out.println("Foo.value: " + Foo.value());

    System.out.println("FooA.value: " + FooA.value);
    System.out.println("FooB.value: " + FooB.value);
    System.out.println("FooC.value: " + FooC.value);

    System.out.println("MyResource: " + readResource("MyResource.txt"));
    System.out.println("MyOtherResource: " + readResource("MyOtherResource.txt"));

    Properties properties = System.getProperties();
    System.out.println("my.custom.property: " + properties.getProperty("my.custom.property"));

    String myCustomEnv = System.getenv("MY_CUSTOM_ENV");
    if (myCustomEnv != null) {
      System.out.println("MY_CUSTOM_ENV: " + myCustomEnv);
    }
  }

  private static String readResource(String resourceName) {
    try {
      return new String(
          Foo2.class.getClassLoader().getResourceAsStream(resourceName).readAllBytes());
    } catch (IOException e) {
      return null;
    }
  }
}
