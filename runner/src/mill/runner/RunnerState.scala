package mill.runner

import mill.api.{PathRef, internal}
import mill.define.{BaseModule, Segments}
import upickle.default.{ReadWriter, macroRW}
import mill.api.JsonFormatters._
@internal
case class RunnerState(frames: Seq[RunnerState.Frame],
                       errorOpt: Option[String])

object RunnerState{
  def empty = RunnerState(Nil, None)

  @internal
  case class Frame(workerCache: Map[Segments, (Int, Any)],
                   watched: Seq[Watchable],
                   scriptImportGraph: Map[os.Path, Seq[os.Path]],
                   outputClassLoader: Option[java.net.URLClassLoader]){


    lazy val buildHash = outputClassLoader
      .toSeq
      .flatMap(_.getURLs.toSet)
      .map(u => PathRef(os.Path(java.nio.file.Paths.get(u.toURI))))
      .hashCode()

    lazy val scriptHash = scriptImportGraph.keys.toSeq.sorted.map(PathRef(_).sig).sum

    def loggedData = {
      Frame.Logged(
        workerCache.map{case (k, (i, v)) =>
          (k.render, Frame.WorkerInfo(System.identityHashCode(v), i))
        },
        watched.collect{case Watchable.Path(p) => p},
        scriptImportGraph,
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

    def empty = Frame(Map.empty, Nil, Map.empty, null)
  }

}
