package mill.runner

import mill.api.{PathRef, Val, internal}
import mill.runner.api.{RootModuleApi, EvaluatorApi}
import mill.define.Segments
import mill.define.internal.Watchable
import upickle.default.{ReadWriter, macroRW}
import mill.define.RootModule0

/**
 * This contains a list of frames each representing cached data from a single
 * level of `build.mill` evaluation:
 *
 * - `frame(0)` contains the output of evaluating the user-given targets
 * - `frame(1)` contains the output of `build.mill` file compilation
 * - `frame(2)` contains the output of the in-memory [[MillBuildRootModule.BootstrapModule]]
 * - If there are meta-builds present (e.g. `mill-build/build.mill`), then `frame(2)`
 *   would contain the output of the meta-build compilation, and the in-memory
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
    bootstrapModuleOpt: Option[RootModule0],
    frames: Seq[RunnerState.Frame],
    errorOpt: Option[String],
    buildFile: Option[String] = None
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
    val identity: Int = scala.util.Random.nextInt()
  }

  def empty: RunnerState = RunnerState(None, Nil, None)

  @internal
  case class Frame(
      workerCache: Map[String, (Int, Val)],
      evalWatched: Seq[Watchable],
      moduleWatched: Seq[Watchable],
      codeSignatures: Map[String, Int],
      classLoaderOpt: Option[RunnerState.URLClassLoader],
      runClasspath: Seq[PathRef],
      compileOutput: Option[PathRef],
      evaluator: Option[EvaluatorApi]
  ) {

    def loggedData: Frame.Logged = {
      Frame.Logged(
        workerCache.map { case (k, (i, v)) =>
          (k, Frame.WorkerInfo(System.identityHashCode(v), i))
        },
        evalWatched.collect { case Watchable.Path(p, quick, sig) =>
          new PathRef(os.Path(p), quick, sig, mill.api.PathRef.Revalidate.Once)
        },
        moduleWatched.collect { case Watchable.Path(p, quick, sig) =>
          new PathRef(os.Path(p), quick, sig, mill.api.PathRef.Revalidate.Once)
        },
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
        classLoaderIdentity: Option[Int],
        runClasspath: Seq[PathRef],
        runClasspathHash: Int
    )
    implicit val loggedRw: ReadWriter[Logged] = macroRW

    def empty: Frame = Frame(Map.empty, Nil, Nil, Map.empty, None, Nil, None, null)
  }

}
