package mill.entrypoint

import mill.define.{ScriptNode, Segments}

import scala.collection.mutable

class EvaluatorState private (
    _rootModule: mill.define.BaseModule,
    _workerCache: Map[Segments, (Int, Any)],
    _watched: Seq[Watchable],
    _setSystemProperties: Set[String],
    _importTree: Seq[ScriptNode],
    _bootClassloader: java.net.URLClassLoader,
) {
  def rootModule: mill.define.BaseModule = _rootModule
  def workerCache: Map[Segments, (Int, Any)] = _workerCache
  def watched: Seq[Watchable] = _watched
  def setSystemProperties: Set[String] = _setSystemProperties
  def importTree: Seq[ScriptNode] = _importTree
  def bootClassloader: java.net.URLClassLoader = _bootClassloader

  override def toString(): String = {
    s"""EvaluatorState(
       |  rootModule = $rootModule,
       |  workerCache = $workerCache,
       |  watched = $watched,
       |  setSystemProperties = $setSystemProperties,
       |  importTree = $importTree
       |)""".stripMargin
  }
}
object EvaluatorState {
  def apply(
      rootModule: mill.define.BaseModule,
      workerCache: Map[Segments, (Int, Any)],
      watched: Seq[Watchable],
      setSystemProperties: Set[String],
      importTree: Seq[ScriptNode],
      bootClassloader: java.net.URLClassLoader
  ): EvaluatorState = new EvaluatorState(
    rootModule,
    workerCache,
    watched,
    setSystemProperties,
    importTree,
    bootClassloader
  )
}
