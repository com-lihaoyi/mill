package mill.main

import scala.collection.mutable

import mill.define.{ScriptNode, Segments}

class EvaluatorState private (
    _rootModule: mill.define.BaseModule,
    _classLoaderSig: Seq[(Either[String, java.net.URL], Long)],
    _workerCache: mutable.Map[Segments, (Int, Any)],
    _watched: Seq[(mill.internal.Watchable, Long)],
    _setSystemProperties: Set[String],
    _importTree: Seq[ScriptNode]
) {
  def rootModule: mill.define.BaseModule = _rootModule
  def classLoaderSig: Seq[(Either[String, java.net.URL], Long)] = _classLoaderSig
  def workerCache: mutable.Map[Segments, (Int, Any)] = _workerCache
  def watched: Seq[(mill.internal.Watchable, Long)] = _watched
  def setSystemProperties: Set[String] = _setSystemProperties
  def importTree: Seq[ScriptNode] = _importTree

  override def toString(): String = {
    s"""EvaluatorState(
       |  rootModule = $rootModule,
       |  classLoaderSig = $classLoaderSig,
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
      classLoaderSig: Seq[(Either[String, java.net.URL], Long)],
      workerCache: mutable.Map[Segments, (Int, Any)],
      watched: Seq[(mill.internal.Watchable, Long)],
      setSystemProperties: Set[String],
      importTree: Seq[ScriptNode]
  ): EvaluatorState = new EvaluatorState(
    rootModule,
    classLoaderSig,
    workerCache,
    watched,
    setSystemProperties,
    importTree
  )
}
