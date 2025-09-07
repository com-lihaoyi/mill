//| jvmId: "graalvm-community:24"
//| nativeImageOptions: ["--no-fallback"]

public class Bar {

  public static void main(String[] args) {
    System.out.println("Hello Graal Native: " + System.getProperty("java.version"));
  }
}
