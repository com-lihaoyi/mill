package mill.runner

import mill.api.{PathRef, Val, internal}
import mill.define.{BaseModule, Segments}
import mill.util.Watchable
import upickle.default.{ReadWriter, macroRW}
import mill.api.JsonFormatters._
import mill.eval.Evaluator
import mill.main.RootModule

/**
 * This contains a list of frames each representing cached data from a single
 * level of `build.sc` evaluation:
 *
 * - `frame(0)` contains the output of evaluating the user-given targets
 * - `frame(1)` contains the output of `build.sc` file compilation
 * - `frame(2)` contains the output of the in-memory [[MillBuildRootModule.BootstrapModule]]
 * - If there are meta-builds present (e.g. `mill-build/build.sc`), then `frame(2)`
 *   would contains the output of the meta-build compilation, and the in-memory
 *   bootstrap module would be pushed to a higher frame
 *
 * If a level `n` fails to evaluate, then [[errorOpt]] is set to the error message
 * and frames `< n` are set to [[RunnerState.Frame.empty]]
 *
 * Note that frames may be partially populated, e.g. the final level of
 * evaluation populates `watched` but not `scriptImportGraph`,
 * `classLoaderOpt` or `runClasspath` since there are no further levels of
 * evaluation that require them.
 */
@internal
case class RunnerState(
    bootstrapModuleOpt: Option[RootModule],
    frames: Seq[RunnerState.Frame],
    errorOpt: Option[String]
) {
  def add(
      frame: RunnerState.Frame = RunnerState.Frame.empty,
      errorOpt: Option[String] = None
  ): RunnerState = {
    this.copy(frames = Seq(frame) ++ frames, errorOpt = errorOpt)
  }
}

object RunnerState {
  class URLClassLoader(urls: Array[java.net.URL], parent: ClassLoader)
      extends java.net.URLClassLoader(urls, parent) {

    // Random ID of the URLClassLoader to ensure it doesn't
    // duplicate (unlike System.identityHashCode), allowing tests to compare
    // hashcodes to verify whether the classloader has been re-created
    val identity = scala.util.Random.nextInt()
  }

  def empty = RunnerState(None, Nil, None)

  @internal
  case class Frame(
      workerCache: Map[Segments, (Int, Val)],
      evalWatched: Seq[Watchable],
      moduleWatched: Seq[Watchable],
      scriptImportGraph: Map[os.Path, (Int, Seq[os.Path])],
      methodCodeHashSignatures: Map[String, Int],
      classLoaderOpt: Option[RunnerState.URLClassLoader],
      runClasspath: Seq[PathRef],
      evaluator: Evaluator
  ) {

    def loggedData: Frame.Logged = {
      Frame.Logged(
        workerCache.map { case (k, (i, v)) =>
          (k.render, Frame.WorkerInfo(System.identityHashCode(v), i))
        },
        evalWatched.collect { case Watchable.Path(p) => p },
        moduleWatched.collect { case Watchable.Path(p) => p },
        scriptImportGraph,
        classLoaderOpt.map(_.identity),
        runClasspath,
        runClasspath.hashCode()
      )
    }
  }

  object Frame {
    case class WorkerInfo(identityHashCode: Int, inputHash: Int)
    implicit val workerInfoRw: ReadWriter[WorkerInfo] = macroRW

    case class ClassLoaderInfo(identityHashCode: Int, paths: Seq[String], buildHash: Int)
    implicit val classLoaderInfoRw: ReadWriter[ClassLoaderInfo] = macroRW

    /**
     * Simplified representation of [[Frame]] data, written to disk for
     * debugging and testing purposes.
     */
    case class Logged(
        workerCache: Map[String, WorkerInfo],
        evalWatched: Seq[PathRef],
        moduleWatched: Seq[PathRef],
        scriptImportGraph: Map[os.Path, (Int, Seq[os.Path])],
        classLoaderIdentity: Option[Int],
        runClasspath: Seq[PathRef],
        runClasspathHash: Int
    )
    implicit val loggedRw: ReadWriter[Logged] = macroRW

    def empty = Frame(Map.empty, Nil, Nil, Map.empty, Map.empty, None, Nil, null)
  }

}
