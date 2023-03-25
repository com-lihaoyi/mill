package mill.main

import scala.collection.mutable
import mill.define.{ScriptNode, Segments}

class EvaluatorState private (
    _rootModule: mill.define.BaseModule,
    _classLoaderSig: Seq[(Either[String, java.net.URL], Long)],
    _workerCache: mutable.Map[Segments, (Int, Any)],
    _watched: Seq[mill.internal.Watchable],
    _setSystemProperties: Set[String],
    _importTree: Seq[ScriptNode],
    _bootClassloader: java.net.URLClassLoader,
) {
  def rootModule: mill.define.BaseModule = _rootModule
  def classLoaderSig: Seq[(Either[String, java.net.URL], Long)] = _classLoaderSig
  def workerCache: mutable.Map[Segments, (Int, Any)] = _workerCache
  def watched: Seq[mill.internal.Watchable] = _watched
  def setSystemProperties: Set[String] = _setSystemProperties
  def importTree: Seq[ScriptNode] = _importTree
  def bootClassloader: java.net.URLClassLoader = _bootClassloader

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
      watched: Seq[mill.internal.Watchable],
      setSystemProperties: Set[String],
      importTree: Seq[ScriptNode],
      bootClassloader: java.net.URLClassLoader
  ): EvaluatorState = new EvaluatorState(
    rootModule,
    classLoaderSig,
    workerCache,
    watched,
    setSystemProperties,
    importTree,
    bootClassloader
  )
}
