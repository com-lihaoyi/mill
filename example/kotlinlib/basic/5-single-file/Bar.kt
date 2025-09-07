//| jvmId: "graalvm-community:24"
//| nativeImageOptions: ["--no-fallback"]

fun main(args: Array<String>) {
    println("Hello Graal Native: " + System.getProperty("java.version"))
}
