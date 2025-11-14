package foo

object Foo {
    fun classpathResourceText(): String = Foo::class.java.classLoader.getResourceAsStream("file.txt").use {
        it.readAllBytes().toString(Charsets.UTF_8)
    }
}
