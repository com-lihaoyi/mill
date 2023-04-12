package mill.codesig

import os.Path
import utest._
import upickle.default.{ReadWriter, read, readwriter, write}

import scala.collection.immutable.{SortedMap, SortedSet}
object CodeSigTests extends TestSuite{
  val tests = Tests{
    test("hello"){
      val foundTransitive0 = CodeSig.compute(
        os.walk(os.Path(sys.env("TEST_CASE_CLASS_FILES"))).filter(_.ext == "class")
      )

      val expectedTransitive = parseExpectedJson(os.Path(sys.env("TEST_CASE_SOURCE_FILES")))

      val foundTransitive = foundTransitive0
        .map{ case (k, vs) => (k, vs.filter(!_.contains("lambda$"))) }
        .filter { case (k, vs) => !k.contains("lambda$") && !k.contains("$deserializeLambda$")  && !k.contains("$anonfun$") && vs.nonEmpty }
        .to(SortedMap)
        .map{case (k, vs) => (k, vs.to(SortedSet))}

      val expectedTransitiveJson = write(expectedTransitive, indent = 4)
      val foundTransitiveJson = write(foundTransitive, indent = 4)

      assert(expectedTransitiveJson == foundTransitiveJson)
      foundTransitiveJson
    }
  }

  def parseExpectedJson(testCaseSourceFilesRoot: Path) = {
    val possibleSources = Seq("Hello.java", "Hello.scala")
    val sourceLines = possibleSources
      .map(testCaseSourceFilesRoot / _)
      .find(os.exists(_))
      .map(os.read.lines(_))
      .get

    val expectedTransitiveLines = sourceLines
      .dropWhile(_ != "/* EXPECTED TRANSITIVE")
      .drop(1)
      .takeWhile(_ != "*/")

    val expectedTransitive = read[SortedMap[String, SortedSet[String]]](
      expectedTransitiveLines.mkString("\n")
    )
    expectedTransitive
  }
}
