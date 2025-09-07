//| jvmId: "graalvm-community:24"
//| nativeImageOptions: ["--no-fallback"]
//| publishVersion: "0.0.1"
//| artifactName: "example"
//| pomSettings:
//|   description: "Hello"
//|   organization: "com.lihaoyi"
//|   url: "https://github.com/lihaoyi/example"
//|   licenses: ["MIT"]
//|   versionControl: {}
//|   developers: []
public class Bar {

  public static void main(String[] args) {
    System.out.println("Hello Graal Native: " + System.getProperty("java.version"));
  }
}
