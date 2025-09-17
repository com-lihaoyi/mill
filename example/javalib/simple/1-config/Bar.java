//| extends: mill.simple.Java.Publish
//| jvmId: "graalvm-community:24"
//| nativeImageOptions: ["--no-fallback"]
//| publishVersion: "0.0.1"
//| artifactName: "example"
//| pomSettings:
//|   description: "Example"
//|   organization: "com.lihaoyi"
//|   url: "https://github.com/com.lihaoyi/example"
//|   licenses: ["MIT"]
//|   versionControl: "https://github.com/com.lihaoyi/example"
//|   developers: [{"name": "Li Haoyi", "email": "example@example.com"}]

public class Bar {
  public static void main(String[] args) {
    System.out.println("Hello Graal Native: " + System.getProperty("java.version"));
  }
}
