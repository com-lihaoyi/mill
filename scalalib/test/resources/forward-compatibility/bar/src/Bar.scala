object Bar:
  @main def run() =
    // Assert that `Foo` (which was compiled with a newer compiler) is accessible
    assert(Foo.numbers == Seq(1, 2, 3))

    // Assert that the desired version of stdlib is present on the classpath at runtime
    val canEqualMethods = classOf[CanEqual.type].getMethods.toList
    assert( canEqualMethods.exists(_.getName == "canEqualSeq")) // since 3.0.x
    assert(!canEqualMethods.exists(_.getName == "canEqualSeqs")) // since 3.1.x
