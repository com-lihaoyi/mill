package mill.codesig

import os.Path
import utest._
import upickle.default.{ReadWriter, read, readwriter, write}

import scala.collection.immutable.{SortedMap, SortedSet}
object CodeSigTests extends TestSuite{
  val tests = Tests{
    test("hello"){
      val callGraph0 = CodeSig.compute(
        os.walk(os.Path(sys.env("TEST_CASE_CLASS_FILES"))).filter(_.ext == "class")
      )

      val expectedTransitive = parseExpectedJson(os.Path(sys.env("TEST_CASE_SOURCE_FILES")))


      val foundTransitive = simplifyCallGraph(
        callGraph0,
        skipped = Seq(
          "lambda$",
          "$deserializeLambda$",
          "$anonfun$",
          "<clinit>",
          "$adapted",
          "$init$",
        )
      )

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

  /**
   * Removes noisy methods from the given call-graph, simplifying it for ease
   * of understanding and testing.
   *
   * Uses an `O(n^2)` algorithm for processing the graph. Can probably be
   * optimized further if necessary, but for testing purposes all the graphs
   * are small so it's probably fine.
   */
  def simplifyCallGraph(callGraph0: Map[MethodDef, Set[MethodDef]],
                        skipped: Seq[String]) = {
    val stringCallGraph0 = callGraph0
      .map { case (k, vs) => (k.toString, vs.map(_.toString)) }
      .to(collection.mutable.Map)

    for(k <- stringCallGraph0.keySet){
      if (skipped.exists(k.contains(_))){
        val removed = stringCallGraph0.remove(k).get
        for(k2 <- stringCallGraph0.keySet){
          stringCallGraph0.updateWith(k2){ case Some(vs) =>
            Some(vs.flatMap(v => if (v == k) removed else Set(v)))
          }
        }
      }
    }

    stringCallGraph0.to(SortedMap)
      .collect { case (k, vs) if vs.nonEmpty => (k, vs.to(SortedSet)) }
  }
}
