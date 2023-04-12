package mill.codesig
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import utest._
import upickle.default.{ReadWriter, read, readwriter, write}

import scala.collection.immutable.{SortedMap, SortedSet}
object CodeSigTests extends TestSuite{

  def loadClass(bytes: Array[Byte]) = {
    val classReader = new ClassReader(bytes)
    val classNode = new ClassNode()
    classReader.accept(classNode, 0)
    classNode
  }

  val tests = Tests{
    test("hello"){
      val testCaseClassFilesRoot = os.Path(sys.env("TEST_CASE_CLASS_FILES"))
      val testCaseSourceFilesRoot = os.Path(sys.env("TEST_CASE_SOURCE_FILES"))

      val classFiles = os.walk(testCaseClassFilesRoot).filter(_.ext == "class")
      val classNodes = classFiles.map(p => loadClass(os.read.bytes(p)))

      val summary = LocalSummarizer.summarize(classNodes)

      val allDirectAncestors = summary.directAncestors.flatMap(_._2)
      val allMethodCallParamClasses = summary
        .callGraph
        .flatMap(_._2._2)
        .flatMap(_.desc.args)
        .collect{case c: JType.Cls => c}
      val external = ExternalSummarizer.loadAll(
        (allDirectAncestors ++ allMethodCallParamClasses)
          .filter(!summary.directAncestors.contains(_))
          .toSet,
        externalType =>
          loadClass(os.read.bytes(os.resource / os.SubPath(externalType.name.replace('.', '/') + ".class")))
      )

      val foundTransitive0 = Analyzer.analyze(summary, external)
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
