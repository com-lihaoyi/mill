//| jvmId: "graalvm-community:24"
//| nativeImageOptions: ["--no-fallback"]

def main(args: Array[String]): Unit = {
  println("Hello Graal Native: " + System.getProperty("java.version"))
}
