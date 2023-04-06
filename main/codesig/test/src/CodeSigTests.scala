package mill.codesig
import utest._
import upickle.default.{ReadWriter, readwriter, read, write}
import scala.collection.immutable.{SortedMap, SortedSet}
object CodeSigTests extends TestSuite{
  val tests = Tests{
    test("hello"){
      val testCaseClassFilesRoot = os.Path(sys.env("TEST_CASE_CLASS_FILES"))
      val testCaseSourceFilesRoot = os.Path(sys.env("TEST_CASE_SOURCE_FILES"))

      val classFiles = os.walk(testCaseClassFilesRoot).filter(_.ext == "class")
      val classNodes = classFiles.map(p => Summarizer.loadClass(os.read.bytes(p)))

      val summary = Summarizer.summarize0(classNodes)

      val analyzer = new Analyzer(summary)

      val foundTransitive0 = analyzer
        .transitiveCallGraphMethods
        .map{case (k, vs) => (k.toString, vs.map(_.toString))}

      implicit def sortedMapRw[K: ReadWriter: Ordering, V: ReadWriter] =
        readwriter[Map[K, V]].bimap[SortedMap[K, V]](
          c => c.toMap,
          c => c.to(SortedMap): SortedMap[K, V]
        )

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

      val foundTransitive = foundTransitive0
        .map{ case (k, vs) => (k, vs.filter(!_.contains("lambda$"))) }
        .filter { case (k, vs) => !k.contains("lambda$") && !k.contains("$deserializeLambda$")  && !k.contains("$anonfun$") && vs.nonEmpty }

      val expectedTransitiveJson = write(
        expectedTransitive.map{case (k, vs) => (k, vs)},
        indent = 4
      )
      val foundTransitiveJson = write(
        foundTransitive.to(SortedMap).map{case (k, vs) => (k, vs.to(SortedSet))},
        indent = 4
      )
      assert(expectedTransitiveJson == foundTransitiveJson)
      foundTransitiveJson
    }
  }
}
