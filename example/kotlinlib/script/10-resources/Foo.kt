package foo

object Foo {
    fun classpathResourceText(): String {
        return Foo::class.java.classLoader.getResourceAsStream("file.txt").use {
            it.readAllBytes().toString(Charsets.UTF_8)
        }
    }
}
