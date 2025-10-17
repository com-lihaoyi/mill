package foo;

public class Foo {
  public static void main(String[] args) {
    String jvmProperty = System.getProperty("my.jvm.property");
    String envVar = System.getenv("MY_ENV_VAR");
    System.out.println(jvmProperty + " " + envVar);
    if (false) Thread.currentThread().stop();
  }
}
