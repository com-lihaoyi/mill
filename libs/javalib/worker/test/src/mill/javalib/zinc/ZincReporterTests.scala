package mill.javalib.zinc

import mill.api.daemon.internal.{CompileProblemReporter, Problem}
import mill.javalib.worker.RecordingReporter
import sbt.internal.inc.{Analysis, ManagedLoggedReporter, SourceInfos}
import sbt.internal.inc.VirtualFileUtil.sourcePositionMapper
import sbt.mill.SbtLoggerUtils
import sbt.util.InterfaceUtil
import utest.*
import xsbti.{Severity, VirtualFileRef}

import java.util.Optional
import scala.collection.mutable

/** Tests that the reporters show real paths and replay cached problems correctly. */
object ZincReporterTests extends TestSuite {
  val id = "${WORKSPACE}/foo/src/U.scala"
  // As stored in the analysis, with Zinc's file id
  val pos = new xsbti.Position {
    def line() = Optional.of[Integer](1)
    def lineContent() = "class U"
    def offset() = Optional.of[Integer](0)
    def pointer() = Optional.of[Integer](0)
    def pointerSpace() = Optional.of("")
    def sourcePath() = Optional.of(id)
    def sourceFile() = Optional.of(java.io.File(id))
  }

  // Built as in `ZincWorker.compileInternal`, for a client without color
  def reporterFor(ws: os.Path) = {
    val printed = mutable.Buffer[String]()
    val forwarded = mutable.Buffer[Problem]()
    val forwarder = new CompileProblemReporter {
      def start(): Unit = ()
      def logError(problem: Problem): Unit = forwarded += problem
      def logWarning(problem: Problem): Unit = forwarded += problem
      def logInfo(problem: Problem): Unit = forwarded += problem
      def fileVisited(file: java.nio.file.Path): Unit = ()
      def printSummary(): Unit = ()
      def finish(): Unit = ()
      def notifyProgress(progress: Long, total: Long): Unit = ()
    }
    val logger = SbtLoggerUtils.createLogger(
      "ZincReporterTests",
      SbtLoggerUtils.ConciseLevelConsoleAppender("test", printed += _, _ => (), false),
      sbt.util.Level.Info
    )
    val pm = sourcePositionMapper(ZincWorker.fileConverter(Seq("WORKSPACE" -> ws.toString)))
    val reporter = new ManagedLoggedReporter(10, logger, pm)
      with ForwardingReporter(forwarder)
      with RecordingReporter
      with TransformingReporter(false, ws) {}
    (reporter, printed, forwarded)
  }

  // Replays `problems` as stored in the analysis
  def replay(reporter: RecordingReporter, problems: xsbti.Problem*): Unit =
    reporter.logOldProblems(Analysis.empty.copy(infos =
      SourceInfos.empty.add(VirtualFileRef.of(id), SourceInfos.makeInfo(problems, Nil, Nil))
    ))

  val tests: Tests = Tests {
    // A warning the original checkout stored with colored code, replayed in a copy
    test("replayedProblemsShowRealPaths") {
      val copy = os.temp.dir() / "copy"
      val (reporter, printed, forwarded) = reporterFor(copy)
      val rendered = s"-- Warning: $id:1:0\n1 |\u001b[33mclass\u001b[0m U\n  |^"
      replay(
        reporter,
        InterfaceUtil.problem("", pos, "deprecated", Severity.Warn, Some(rendered), None, Nil, Nil)
      )

      assert(printed.exists(_.startsWith("[warn] foo/src/U.scala:1:1")))
      assert(printed.exists(_.contains("class U")))
      assert(!printed.exists(s => s.contains("${") || s.contains("\u001b")))
      val Seq(fwdPos) = forwarded.toSeq.map(_.position)
      assert(fwdPos.sourcePath.map(os.Path(_)).contains(copy / "foo/src/U.scala"))
      assert(fwdPos.sourceFile.contains((copy / "foo/src/U.scala").toIO))
      // Zinc's analysis gets the problem as reported, with Zinc's file id
      assert(reporter.problems.map(_.position.sourcePath.get).toSeq == Seq(id))
    }

    // A problem reported in this compile isn't replayed from the analysis again, though the
    // compiler bridges' problems don't compare by value: Scala 3's makes a new DiagnosticCode on
    // every call, so the copy in the analysis has another one
    test("freshProblemsAreNotReplayed") {
      val (reporter, printed, _) = reporterFor(os.temp.dir())
      def newCode = new xsbti.DiagnosticCode {
        def code(): String = "E1"
        def explanation(): Optional[String] = Optional.empty()
      }
      def note() =
        InterfaceUtil.problem("", pos, "a note", Severity.Info, None, Some(newCode), Nil, Nil)
      reporter.log(note())
      replay(reporter, note())
      assert(printed.count(_.contains("a note")) == 1)
    }

    // ...but the same problem in another file is
    test("sameProblemInAnotherFileIsReplayed") {
      val (reporter, printed, _) = reporterFor(os.temp.dir())
      val otherId = "${WORKSPACE}/foo/src/V.scala"
      val otherPos = new xsbti.Position {
        def line() = pos.line()
        def lineContent() = pos.lineContent()
        def offset() = pos.offset()
        def pointer() = pos.pointer()
        def pointerSpace() = pos.pointerSpace()
        def sourcePath() = Optional.of(otherId)
        def sourceFile() = Optional.of(java.io.File(otherId))
      }
      def note(p: xsbti.Position) =
        InterfaceUtil.problem("", p, "a note", Severity.Info, None, None, Nil, Nil)
      reporter.log(note(pos))
      reporter.logOldProblems(Analysis.empty.copy(infos =
        SourceInfos.empty
          .add(VirtualFileRef.of(id), SourceInfos.makeInfo(Seq(note(pos)), Nil, Nil))
          .add(VirtualFileRef.of(otherId), SourceInfos.makeInfo(Seq(note(otherPos)), Nil, Nil))
      ))
      assert(printed.count(_.contains("a note")) == 2)
    }
  }
}
