package mill.codesig

import os.Path
import utest._
import upickle.{read, write}
import scala.collection.immutable.{SortedMap, SortedSet}

/**
 * Tests to make sure the direct call graph and transitive call graphs are what
 * we expect for a variety of trivial and slightly-less-trivial examples.
 */
object CallGraphTests extends TestSuite {
  val tests = Tests {
    test("basic") {
      test("1-static-method") - testExpectedCallGraph()
      test("2-instance-method") - testExpectedCallGraph()
      test("3-sam-interface-method") - testExpectedCallGraph()
      test("4-multi-interface-method") - testExpectedCallGraph()
      test("5-inherited-method") - testExpectedCallGraph()
      test("6-inherited-interface-method") - testExpectedCallGraph()
      test("7-transitive-static-methods") - testExpectedCallGraph()
      test("8-transitive-virtual-methods") - testExpectedCallGraph()
      test("9-overridden-virtual-method") - testExpectedCallGraph()
      test("10-overridden-static-method") - testExpectedCallGraph()
      test("11-peer-inherited-method") - testExpectedCallGraph()
      test("12-java-lambda") - testExpectedCallGraph()
      test("13-java-anon-class-lambda") - testExpectedCallGraph()
      test("14-clinit") - testExpectedCallGraph()
      test("15-private-method-not-inherited") - testExpectedCallGraph()
      test("16-scala-static-method") - testExpectedCallGraph()
      test("17-scala-lambda") - testExpectedCallGraph()
      test("18-scala-anon-class-lambda") - testExpectedCallGraph()
      test("19-scala-trait-constructor") - testExpectedCallGraph()
      test("20-array-method") - testExpectedCallGraph()
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
      test("1-sam-interface-method") - testExpectedCallGraph()
      test("2-multi-interface-method") - testExpectedCallGraph()
      test("3-sam-interface-never-instantiated") - testExpectedCallGraph()
      test("4-multi-interface-never-instantiated") - testExpectedCallGraph()
      test("5-sam-interface-never-called") - testExpectedCallGraph()
      test("6-multi-interface-never-called") - testExpectedCallGraph()
      test("7-sam-abstract-class-maybe-called") - testExpectedCallGraph()
      test("8-multi-abstract-class-maybe-called") - testExpectedCallGraph()
      test("9-abstract-class-indirect-inheritance-called") - testExpectedCallGraph()
      test("10-abstract-class-indirect-inheritance-not-called") - testExpectedCallGraph()
      test("11-abstract-class-indirect-delegation-called") - testExpectedCallGraph()
      test("12-abstract-class-indirect-delegation-uncalled") - testExpectedCallGraph()
      test("13-interface-two-implementations-interface-call") - testExpectedCallGraph()
      test("14-interface-two-implementations-direct-call") - testExpectedCallGraph()
      test("15-static-method") - testExpectedCallGraph()
      test("16-external-method-edge-to-inherited-method-override") - testExpectedCallGraph()
      test("17-jcanvas") - testExpectedCallGraph()
      test("18-external-method-calls-parent-method") - testExpectedCallGraph()
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
    val codeSig = TestUtil.computeCodeSig(Seq("callgraph") ++ tp.value)
    val testCaseSourceFilesRoot =
      os.Path(sys.env("MILL_TEST_SOURCES_callgraph-" + tp.value.mkString("-")))

    val skipped = Seq(
      "lambda$",
      "$deserializeLambda$",
      "$anonfun$",
      "<clinit>",
      "$adapted",
      "$init$",
      "$macro"
    )

    val directCallGraph = testDirectCallGraph(testCaseSourceFilesRoot, codeSig, skipped)

    testTransitiveCallGraph(testCaseSourceFilesRoot, codeSig, skipped)

    directCallGraph
  }

  /**
   * Make sure the direct call graph contains what we expect
   */
  def testDirectCallGraph(
      testCaseSourceFilesRoot: os.Path,
      codeSig: CallGraphAnalysis,
      skipped: Seq[String]
  ) = {
    val expectedCallGraph = parseJson(testCaseSourceFilesRoot, "expected-direct-call-graph")
      .getOrElse(sys.error(s"Cannot find json in path $testCaseSourceFilesRoot"))

    val foundCallGraph = simplifyCallGraph(codeSig, skipped)

    val expectedCallGraphJson = write(expectedCallGraph, indent = 2)
    val foundCallGraphJson = write(foundCallGraph, indent = 2)

    assert(expectedCallGraphJson == foundCallGraphJson)
    foundCallGraphJson
  }

  /**
   * Exercise the code computing the transitive call graph summary from the direct call graph
   *
   * Computes a `SortedSet[String]` rather than an `Int` like we do for real usage because it's
   * easier to read and make sense of the summary for each node that way, but shares most of
   * the logic and so should hopefully catch most bugs in the transitive logic anyway
   */
  def testTransitiveCallGraph(
      testCaseSourceFilesRoot: os.Path,
      codeSig: CallGraphAnalysis,
      skipped: Seq[String]
  ) = {

    val expectedTransitiveGraphOpt =
      parseJson(testCaseSourceFilesRoot, "expected-transitive-call-graph")

    val transitiveGraph0 = codeSig.transitiveCallGraphValues[SortedSet[String]](
      codeSig.indexToNodes.map(x => SortedSet(upickle.writeJs(x).str)),
      _ | _,
      SortedSet.empty[String]
    )

    val transitiveGraph = transitiveGraph0
      .collect { case (CallGraphAnalysis.LocalDef(d), vs) =>
        (
          d.toString,
          vs.filter(v => !skipped.exists(v.contains))
            .collect { case s"def $rest" if rest != d.toString => rest }
        )
      }
      .filter { case (k, vs) => !skipped.exists(k.contains) && vs.nonEmpty }
      .to(SortedMap)

    for (expectedTransitiveGraph <- expectedTransitiveGraphOpt) {
      val expectedTransitiveGraphJson = upickle.write(expectedTransitiveGraph, indent = 2)
      val transitiveGraphJson = upickle.write(transitiveGraph, indent = 2)
      assert(expectedTransitiveGraphJson == transitiveGraphJson)
    }
  }

  def parseJson(testCaseSourceFilesRoot: Path, tag: String) = {
    val jsonTextOpt =
      if (os.exists(testCaseSourceFilesRoot / s"$tag.json")) {
        Some(os.read(testCaseSourceFilesRoot / s"$tag.json"))
      } else {
        val possibleSources = Seq("Hello.java", "Hello.scala")
        val sourceLinesOpt = possibleSources
          .map(testCaseSourceFilesRoot / _)
          .find(os.exists(_))
          .map(os.read.lines(_))

        val openTagLine = s"/* $tag"
        sourceLinesOpt.flatMap { sourceLines =>
          sourceLines.count(_ == openTagLine) match {
            case 0 => None
            case 1 =>
              val expectedLines = sourceLines
                .dropWhile(_ != openTagLine)
                .drop(1)
                .takeWhile(l => l != "*/" && l != " */")

              Some(expectedLines.mkString("\n"))
            case _ => sys.error(s"Only one occurrence of line \"$openTagLine\" is expected in file")
          }
        }
      }

    jsonTextOpt.map(read[SortedMap[String, SortedSet[String]]](_))
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
            .filter(d => !d.toString.contains("$sp"))
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
