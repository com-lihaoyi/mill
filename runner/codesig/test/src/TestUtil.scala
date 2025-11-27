package mill.codesig

import scala.collection.immutable.ArraySeq

object TestUtil {

  def computeCodeSig(segments: Seq[String]) = {
    val testLogFolder = os.Path(sys.env("MILL_TEST_LOGS")) / segments
    os.remove.all(testLogFolder)
    os.makeDir.all(testLogFolder)
//    println("testLogFolder: " + testLogFolder)
    val testClassFolder = os.Path(sys.env("MILL_TEST_CLASSES_" + segments.mkString("-")))
//    println("testClassFolder: " + testClassFolder)
    CodeSig.compute(
      os.walk(testClassFolder).filter(_.ext == "class"),
      ArraySeq.unsafeWrapArray(
        sys.env("MILL_TEST_CLASSPATH_" + segments.mkString("-"))
          .split(",")
          .map(os.Path(_))
      ),
      (_, _) => false,
      Logger(testLogFolder, Some(testLogFolder)),
      () => None
    )
  }

}
