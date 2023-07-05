package mill.codesig

import os.Path
import utest._
import upickle.default.{read, write}
import scala.collection.immutable.{SortedMap, SortedSet}
object CallGraphTests extends TestSuite {
  val tests = Tests {
    test("basic") {
      test("1-static-method") - testExpectedCallGraph()
      test("2-instance-method") - testExpectedCallGraph()
      test("3-interface-method") - testExpectedCallGraph()
      test("4-inherited-method") - testExpectedCallGraph()
      test("5-inherited-interface-method") - testExpectedCallGraph()
      test("6-transitive-static-methods") - testExpectedCallGraph()
      test("7-transitive-virtual-methods") - testExpectedCallGraph()
      test("8-overriden-virtual-method") - testExpectedCallGraph()
      test("9-overriden-static-method") - testExpectedCallGraph()
      test("10-peer-inherited-method") - testExpectedCallGraph()
      test("11-java-lambda") - testExpectedCallGraph()
      test("12-clinit") - testExpectedCallGraph()
      test("13-private-method-not-inherited") - testExpectedCallGraph()
      test("14-scala-static-method") - testExpectedCallGraph()
      test("15-scala-lambda") - testExpectedCallGraph()
      test("16-scala-trait-constructor") - testExpectedCallGraph()
    }
    test("complicated") {
      test("1-statics") - testExpectedCallGraph()
      test("2-sudoku") - testExpectedCallGraph()
      test("3-classes-cars") - testExpectedCallGraph()
      test("4-classes-parent") - testExpectedCallGraph()
      test("5-classes-sheep") - testExpectedCallGraph()
      test("6-classes-misc-scala") - testExpectedCallGraph()
      test("7-manifest-scala") - testExpectedCallGraph()
      test("8-linked-list-scala") - testExpectedCallGraph()
      test("9-array-seq-scala") - testExpectedCallGraph()
      test("10-iterator-foreach-scala") - testExpectedCallGraph()
      test("11-iterator-callback-class-scala") - testExpectedCallGraph()
      test("12-iterator-inherit-external-scala") - testExpectedCallGraph()
      test("13-iterator-inherit-external-filter-scala") - testExpectedCallGraph()
      test("14-singleton-objects-scala") - testExpectedCallGraph()

    }

    test("external") {
      test("1-interface-method") - testExpectedCallGraph()
      test("2-interface-never-instantiated") - testExpectedCallGraph()
      test("3-interface-never-called") - testExpectedCallGraph()
      test("4-abstract-class-maybe-called") - testExpectedCallGraph()
      test("5-abstract-class-indirect-inheritance-called") - testExpectedCallGraph()
      test("6-abstract-class-indirect-inheritance-not-called") - testExpectedCallGraph()
      test("7-abstract-class-indirect-delegation-called") - testExpectedCallGraph()
      test("8-abstract-class-indirect-delegation-uncalled") - testExpectedCallGraph()
      test("9-interface-two-implementations-interface-call") - testExpectedCallGraph()
      test("10-interface-two-implementations-direct-call") - testExpectedCallGraph()
      test("11-static-method") - testExpectedCallGraph()
      test("12-external-method-edge-to-inherited-method-override") - testExpectedCallGraph()
      test("13-jcanvas") - testExpectedCallGraph()
      test("14-external-method-calls-parent-method") - testExpectedCallGraph()
    }
    test("realistic") {
      test("1-tetris") - testExpectedCallGraph()
      test("2-ribbon") - testExpectedCallGraph()
      test("3-par-merge-sort") - testExpectedCallGraph()
      test("4-actors") - testExpectedCallGraph()
      test("5-parser") - testExpectedCallGraph()
    }
  }

  def testExpectedCallGraph()(implicit tp: utest.framework.TestPath) = {
    val callGraph0 = TestUtil.computeCodeSig(Seq("callgraph") ++ tp.value)

    val expectedCallGraph = parseExpectedJson(
      os.Path(sys.env("MILL_TEST_SOURCES_callgraph-" + tp.value.mkString("-")))
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
        "$macro"
      )
    )

    val expectedCallGraphJson = write(expectedCallGraph, indent = 4)
    val foundCallGraphJson = write(foundCallGraph, indent = 4)

    assert(expectedCallGraphJson == foundCallGraphJson)
    foundCallGraphJson
  }

  def parseExpectedJson(testCaseSourceFilesRoot: Path) = {
    val jsonText =
      if (os.exists(testCaseSourceFilesRoot / "expected-call-graph.json")) {
        os.read(testCaseSourceFilesRoot / "expected-call-graph.json")
      } else {
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
  def simplifyCallGraph(codeSig: CallGraphAnalysis, skipped: Seq[String]) = {
    import codeSig._
//    pprint.log(codeSig.simplifiedCallGraph(_.toString))

    def simplifiedCallGraph0[T](transform: PartialFunction[CallGraphAnalysis.Node, T])
        : Map[T, Set[T]] = {

      def flatten(ns: Set[CallGraphAnalysis.Node]): Set[T] = {
        if (ns.isEmpty) Set()
        else {
          val (notDefined, defined) = ns.partitionMap(n =>
            transform.lift(n) match {
              case None => Left(n)
              case Some(v) => Right(v)
            }
          )

          val downstream = flatten(
            notDefined.flatMap(n => indexGraphEdges(nodeToIndex(n))).map(indexToNodes)
          )

          defined ++ downstream
        }
      }

      indexGraphEdges
        .zipWithIndex
        .flatMap { case (destIndices, srcIndex) =>
          transform.lift(indexToNodes(srcIndex))
            .map((_, flatten(destIndices.map(destIndex => indexToNodes(destIndex)).toSet)))
        }
        .toMap
    }

    simplifiedCallGraph0 {
      case CallGraphAnalysis.LocalDef(d) if !skipped.exists(d.toString.contains(_)) => d.toString
    }
      .collect { case (k, vs) if vs.nonEmpty => (k, vs.to(SortedSet)) }
      .to(SortedMap)
  }
}
