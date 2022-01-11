package mill.main

import scala.collection.mutable

import mill.define.{ScriptNode, Segments}

case class EvaluatorState private[main] (
    rootModule: mill.define.BaseModule,
    classLoaderSig: Seq[(Either[String, java.net.URL], Long)],
    workerCache: mutable.Map[Segments, (Int, Any)],
    watched: Seq[(ammonite.interp.Watchable, Long)],
    setSystemProperties: Set[String],
    importTree: Seq[ScriptNode]
)
