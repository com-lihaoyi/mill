package foo

fun getString() = "Hello, world"

fun main() {
    println(getString())

    val parsedJsonStr: dynamic = JSON.parse("""{"helloworld": ["hello", "world", "!"]}""")
    val stringifiedJsObject = JSON.stringify(parsedJsonStr.helloworld)
    println("stringifiedJsObject: " + stringifiedJsObject)
}
