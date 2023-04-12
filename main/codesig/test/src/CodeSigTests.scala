package mill.codesig

import os.Path
import utest._
import upickle.default.{ReadWriter, read, readwriter, write}

import scala.collection.immutable.{SortedMap, SortedSet}
object CodeSigTests extends TestSuite{
  val tests = Tests{
    test("basic"){
      test("1-static-method") - testCase()
      test("2-instance-method") - testCase()
      test("3-interface-method") - testCase()
      test("4-inherited-method") - testCase()
      test("5-inherited-interface-method") - testCase()
      test("6-transitive-static-methods") - testCase()
      test("7-transitive-virtual-methods") - testCase()
      test("8-overriden-virtual-method") - testCase()
      test("9-overriden-static-method") - testCase()
      test("10-peer-inherited-method") - testCase()
      test("11-java-lambda") - testCase()
      test("12-external-interface-method") - testCase()
      test("13-external-interface-never-instantiated") - testCase()
      test("14-external-interface-never-called") - testCase()
      test("15-indirect-inheritance-external-interface-method") - testCase()
      test("16-indirect-delegation-external-interface-called") - testCase()
      test("17-indirect-delegation-external-interface-uncalled") - testCase()
      test("18-clinit") - testCase()
      test("20-scala-static-method") - testCase()
      test("21-scala-lambda") - testCase()
    }
    test("complicated"){
      test("1-statics") - testCase()
      test("2-sudoku") - testCase()
      test("3-classes-cars") - testCase()
      test("4-classes-parent") - testCase()
      test("5-classes-sheep") - testCase()
      test("6-classes-misc-scala") - testCase()
      test("7-manifest-scala") - testCase()
      test("8-linked-list-scala") - testCase()
      test("9-array-seq-scala") - testCase()
      test("10-iterator-foreach-scala") - testCase()
      test("11-iterator-callback-class-scala") - testCase()
      test("12-iterator-inherit-external-scala") - testCase()
      test("13-iterator-inherit-external-filter-scala") - testCase()
    }
  }

  def testCase()(implicit tp: utest.framework.TestPath) = {

    val callGraph0 = CodeSig.compute(
      os.walk(os.Path(sys.env("MILL_TEST_" + tp.value.mkString("-"))))
        .filter(_.ext == "class")
    )

    val expectedCallGraph = parseExpectedJson(
      os.pwd / "main" / "codesig" / "test" / tp.value / "src"
    )

    val foundCallGraph = simplifyCallGraph(
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

    val expectedCallGraphJson = write(expectedCallGraph, indent = 4)
    val foundCallGraphJson = write(foundCallGraph, indent = 4)

    assert(expectedCallGraphJson == foundCallGraphJson)
    foundCallGraphJson
  }

  def parseExpectedJson(testCaseSourceFilesRoot: Path) = {
    val possibleSources = Seq("Hello.java", "Hello.scala")
    val sourceLines = possibleSources
      .map(testCaseSourceFilesRoot / _)
      .find(os.exists(_))
      .map(os.read.lines(_))
      .get

    val expectedLines = sourceLines
      .dropWhile(_ != "/* EXPECTED DEPENDENCIES")
      .drop(1)
      .takeWhile(_ != "*/")

    read[SortedMap[String, SortedSet[String]]](expectedLines.mkString("\n"))
  }

  /**
   * Removes noisy methods from the given call-graph, simplifying it for ease
   * of understanding and testing. For every node removed, we redirect any
   * edges to that node with that node's own outgoing edges
   *
   * Uses an `O(n^2)` algorithm for processing the graph. Can probably be
   * optimized further if necessary, but for testing purposes all the graphs
   * are small so it's probably fine.
   */
  def simplifyCallGraph(callGraph0: Map[ResolvedMethodDef, Set[ResolvedMethodDef]],
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
