package mill.runner

import mill.api.{PathRef, internal}
import mill.define.{BaseModule, Segments}


@internal
case class RunnerState(evalStates: Seq[RunnerState.Frame],
                       errorAndDepth: Option[(String, Int)])

object RunnerState{
  def empty = RunnerState(Nil, None)

  @internal
  case class Frame(workerCache: Map[Segments, (Int, Any)],
                   watched: Seq[Watchable],
                   scriptImportGraph: Map[os.Path, Seq[os.Path]],
                   classLoader: java.net.URLClassLoader){
    lazy val cls = classLoader.loadClass("millbuild.build$")
    lazy val buildModule = cls.getField("MODULE$").get(cls).asInstanceOf[BaseModule]
    lazy val buildHash = classLoader
      .getURLs
      .map(u => PathRef(os.Path(java.nio.file.Paths.get(u.toURI))))
      .hashCode()
  }

}
