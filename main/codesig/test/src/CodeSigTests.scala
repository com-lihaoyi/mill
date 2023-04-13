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
      test("12-clinit") - testCase()
      test("13-private-method-not-inherited") - testCase()
      test("14-scala-static-method") - testCase()
      test("15-scala-lambda") - testCase()
    }
    test("complicated") {
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

    test("external"){
      test("1-interface-method") - testCase()
      test("2-interface-never-instantiated") - testCase()
      test("3-interface-never-called") - testCase()
      test("4-abstract-class-maybe-called") - testCase()
      test("5-abstract-class-indirect-inheritance-called") - testCase()
      test("6-abstract-class-indirect-inheritance-not-called") - testCase()
      test("7-abstract-class-indirect-delegation-called") - testCase()
      test("8-abstract-class-indirect-delegation-uncalled") - testCase()
      test("9-interface-two-implementations-interface-call") - testCase()
      test("10-interface-two-implementations-direct-call") - testCase()
      test("11-static-method") - testCase()
      test("12-external-method-edge-to-inherited-method-override") - testCase()
      test("13-jcanvas") - testCase()
    }
    test("games"){
      test("1-tetris") - testCase()
      test("2-ribbon") - testCase()
    }
    test("handsonscala"){
      test("1-par-merge-sort") - testCase()
      test("2-parser") - testCase()
      test("3-actors") - testCase()
    }
  }

  def testCase()(implicit tp: utest.framework.TestPath) = {

    val testLogFolder = os.Path(sys.env("MILL_TEST_LOGS")) / tp.value
    os.remove.all(testLogFolder)
    os.makeDir.all(testLogFolder)
    println("testLogFolder: " + testLogFolder)
    val testClassFolder = os.Path(sys.env("MILL_TEST_CLASSES_" + tp.value.mkString("-")))
    println("testClassFolder: " + testClassFolder)
    val callGraph0 = CodeSig.compute(
      os.walk(testClassFolder).filter(_.ext == "class"),
      sys.env("MILL_TEST_CLASSPATH_" + tp.value.mkString("-"))
        .split(",")
        .map(os.Path(_)),
      new Logger(Some(testLogFolder))
    )

    val expectedCallGraph = parseExpectedJson(
      os.Path(sys.env("MILL_TEST_SOURCES_" + tp.value.mkString("-")))
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
        "$macro",
      )
    )

    val expectedCallGraphJson = write(expectedCallGraph, indent = 4)
    val foundCallGraphJson = write(foundCallGraph, indent = 4)

    assert(expectedCallGraphJson == foundCallGraphJson)
    foundCallGraphJson
  }

  def parseExpectedJson(testCaseSourceFilesRoot: Path) = {
    val jsonText =
      if (os.exists(testCaseSourceFilesRoot / "expected-call-graph.json")){
        os.read(testCaseSourceFilesRoot / "expected-call-graph.json")
      }else {
        val possibleSources = Seq("Hello.java", "Hello.scala")
        val sourceLines = possibleSources
          .map(testCaseSourceFilesRoot / _)
          .find(os.exists(_))
          .map(os.read.lines(_))
          .getOrElse(sys.error(s"Cannot find json in path $testCaseSourceFilesRoot"))

        val expectedLines = sourceLines
          .dropWhile(_ != "/* EXPECTED CALL GRAPH")
          .drop(1)
          .takeWhile(_ != "*/")

        expectedLines.mkString("\n")
      }
    read[SortedMap[String, SortedSet[String]]](jsonText)
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
