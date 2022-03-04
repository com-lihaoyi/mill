object Bar:
  @main def run() =
    // Assert that `Foo` (which was compiled with a newer compiler) is accessible
    assert(Foo.numbers == Seq(1, 2, 3))

    // Just assure that stdlib TASTy files are properly loaded during compilation
    // and accessing stdlib API doesn't crash at runtime.
    // More precise checks like in the corresponding test for the JVM don't seem to be possible
    // because of lack of runtime reflection in ScalaJS.
    val parser = summon[scala.util.CommandLineParser.FromString[Boolean]]
    assert(parser.fromString("true") == true)
