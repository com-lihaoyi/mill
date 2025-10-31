package foo

object Foo {
  def classpathResourceText = os.read(os.resource / "file.txt")
}
