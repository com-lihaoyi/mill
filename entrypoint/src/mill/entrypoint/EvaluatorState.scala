package mill.entrypoint

import mill.define.Segments

import scala.collection.mutable

class EvaluatorState private (
    _rootModule: mill.define.BaseModule,
    _workerCache: Map[Segments, (Int, Any)],
    _watched: Seq[Watchable],
    _setSystemProperties: Set[String],
    _scriptImportGraph: Map[os.Path, Seq[os.Path]],
    _bootClassloader: java.net.URLClassLoader,
) {
  def rootModule: mill.define.BaseModule = _rootModule
  def workerCache: Map[Segments, (Int, Any)] = _workerCache
  def watched: Seq[Watchable] = _watched
  def setSystemProperties: Set[String] = _setSystemProperties
  def scriptImportGraph: Map[os.Path, Seq[os.Path]] = _scriptImportGraph
  def bootClassloader: java.net.URLClassLoader = _bootClassloader

  override def toString(): String = {
    s"""EvaluatorState(
       |  rootModule = $rootModule,
       |  workerCache = $workerCache,
       |  watched = $watched,
       |  setSystemProperties = $setSystemProperties,
       |  scriptImportGraph = $scriptImportGraph
       |)""".stripMargin
  }
}
object EvaluatorState {
  def apply(
      rootModule: mill.define.BaseModule,
      workerCache: Map[Segments, (Int, Any)],
      watched: Seq[Watchable],
      setSystemProperties: Set[String],
      scriptImportGraph: Map[os.Path, Seq[os.Path]],
      bootClassloader: java.net.URLClassLoader
  ): EvaluatorState = new EvaluatorState(
    rootModule,
    workerCache,
    watched,
    setSystemProperties,
    scriptImportGraph,
    bootClassloader
  )
}
