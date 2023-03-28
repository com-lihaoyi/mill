package mill.runner

import mill.api.{PathRef, internal}
import mill.define.{BaseModule, Segments}
import upickle.default.{ReadWriter, macroRW}
import mill.api.JsonFormatters._

/**
 * This contains a list of frames each representing cached data from a single
 * level of `build.sc` evaluation:
 *
 * - `frame(0)` contains the output of evaluating the user-given targets
 * - `frame(1)` contains the output of `build.sc` file compilation
 * - `frame(2)` contains the output of the in-memory [[MillBuildModule.BootstrapModule]]
 * - If there are meta-builds present (e.g. `mill-build/build.sc`), then `frame(2)`
 *   would contains the output of the meta-build compilation, and the in-memory
 *   bootstrap module would be pushed to a higher frame
 *
 *
 * If a level `n` fails to evaluate, then [[errorOpt]] is set to the error message
 * and frames `< n` are set to [[RunnerState.Frame.empty]]
 *
 */
@internal
case class RunnerState(frames: Seq[RunnerState.Frame],
                       errorOpt: Option[String])

object RunnerState{
  def empty = RunnerState(Nil, None)

  @internal
  case class Frame(outputWorkerCache: Map[Segments, (Int, Any)],
                   outputWatched: Seq[Watchable],
                   outputScriptImportGraph: Map[os.Path, Seq[os.Path]],
                   inputClassloader: Option[java.net.URLClassLoader],
                   outputRunClasspath: Seq[PathRef]){


    lazy val buildHash = inputClassloader
      .toSeq
      .flatMap(_.getURLs.toSet)
      .map(u => PathRef(os.Path(java.nio.file.Paths.get(u.toURI))))
      .hashCode()

    lazy val scriptHash = outputScriptImportGraph.keys.toSeq.sorted.map(PathRef(_).sig).sum

    def loggedData = {
      Frame.Logged(
        outputWorkerCache.map{case (k, (i, v)) =>
          (k.render, Frame.WorkerInfo(System.identityHashCode(v), i))
        },
        outputWatched.collect{case Watchable.Path(p) => p},
        outputScriptImportGraph,
        null
//        if (classLoader == null) null
//        else Frame.ClassLoaderInfo(
//          System.identityHashCode(classLoader),
//          classLoader.getURLs.map(_.toString),
//          buildHash
//        )
      )
    }
  }

  object Frame{
    case class WorkerInfo(identityHashCode: Int, inputHash: Int)
    implicit val workerInfoRw: ReadWriter[WorkerInfo] = macroRW

    case class ClassLoaderInfo(identityHashCode: Int, urls: Seq[String], buildHash: Int)
    implicit val classLoaderInfoRw: ReadWriter[ClassLoaderInfo] = macroRW

    case class Logged(workerCache: Map[String, WorkerInfo],
                      watched: Seq[PathRef],
                      scriptImportGraph: Map[os.Path, Seq[os.Path]],
                      classLoader: ClassLoaderInfo)
    implicit val loggedRw: ReadWriter[Logged] = macroRW

    def empty = Frame(Map.empty, Nil, Map.empty, None, Nil)
  }

}
