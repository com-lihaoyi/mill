package mill.entrypoint

import mill.api.internal
import mill.define.{BaseModule, Segments}

@internal
case class MultiEvaluatorState(evalStates: Seq[EvaluatorState],
                               errorAndDepth: Option[(String, Int)])
object MultiEvaluatorState{
  def empty = MultiEvaluatorState(Nil, None)
}

@internal
case class EvaluatorState(workerCache: Map[Segments, (Int, Any)],
                          watched: Seq[Watchable],
                          scriptImportGraph: Map[os.Path, Seq[os.Path]],
                          classLoader: java.net.URLClassLoader){
  lazy val cls = classLoader.loadClass("millbuild.build$")
  lazy val buildModule = cls.getField("MODULE$").get(cls).asInstanceOf[BaseModule]
}
