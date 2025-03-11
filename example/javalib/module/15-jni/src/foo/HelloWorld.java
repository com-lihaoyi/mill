package foo;

public class HelloWorld {
  // Declare a native method
  public native String sayHello();

  // Load the native library
  static {
    System.load(System.getenv("HELLO_WORLD_BINARY"));
  }

  public static void main(String[] args) {
    HelloWorld helloWorld = new HelloWorld();
    System.out.println(helloWorld.sayHello());
  }
}
