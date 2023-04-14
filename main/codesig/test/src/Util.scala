package mill.codesig

object TestUtil {

  def computeCodeSig(segments: Seq[String]) = {
    val testLogFolder = os.Path(sys.env("MILL_TEST_LOGS")) / segments
    os.remove.all(testLogFolder)
    os.makeDir.all(testLogFolder)
    println("testLogFolder: " + testLogFolder)
    val testClassFolder = os.Path(sys.env("MILL_TEST_CLASSES_" + segments.mkString("-")))
    println("testClassFolder: " + testClassFolder)
    CodeSig.compute(
      os.walk(testClassFolder).filter(_.ext == "class"),
      sys.env("MILL_TEST_CLASSPATH_" + segments.mkString("-"))
        .split(",")
        .map(os.Path(_)),
      new Logger(Some(testLogFolder))
    )
  }

}
