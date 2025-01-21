package foo

class HelloWorld {
    // Declare a native method
    external fun sayHello(): String

    // Load the native library
    companion object {
        init {
            System.load(System.getenv("HELLO_WORLD_BINARY"))
        }
    }
}

fun main(args: Array<String>) {
    val helloWorld = HelloWorld()
    println(helloWorld.sayHello())
}
