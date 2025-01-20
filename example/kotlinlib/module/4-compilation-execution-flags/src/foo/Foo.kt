package foo

fun main() {
    val jvmProperty = System.getProperty("my.jvm.property")
    val envVar = System.getenv("MY_ENV_VAR")
    println("$jvmProperty $envVar")
}
