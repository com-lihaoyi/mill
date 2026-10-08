package bar;

import foo.Foo;

public class Bar {
  public static String greetAll(String[] names) {
    StringBuilder sb = new StringBuilder();
    for (String name : names) {
      if (sb.length() > 0) sb.append(", ");
      sb.append(Foo.greet(name));
    }
    return sb.toString();
  }

  public static void main(String[] args) throws Exception {
    System.out.println(greetAll(new String[] {"Bar", "Qux"}));
  }
}
