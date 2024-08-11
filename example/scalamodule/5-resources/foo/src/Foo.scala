package foo

object Foo {
  // Read `file.txt` from classpath
  def classpathResourceText = os.read(os.resource / "file.txt")
}
