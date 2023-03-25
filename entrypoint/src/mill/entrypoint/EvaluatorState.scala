package mill.entrypoint

import mill.define.Segments

class EvaluatorState private (
    _workerCache: Map[Segments, (Int, Any)],
    _watched: Seq[Watchable],
    _scriptImportGraph: Map[os.Path, Seq[os.Path]],
    _bootClassloader: java.net.URLClassLoader,
) {
  def workerCache: Map[Segments, (Int, Any)] = _workerCache
  def watched: Seq[Watchable] = _watched
  def scriptImportGraph: Map[os.Path, Seq[os.Path]] = _scriptImportGraph
  def bootClassloader: java.net.URLClassLoader = _bootClassloader

  override def toString(): String = {
    s"""EvaluatorState(
       |  workerCache = $workerCache,
       |  watched = $watched,
       |  scriptImportGraph = $scriptImportGraph
       |)""".stripMargin
  }
}
object EvaluatorState {
  def apply(
      workerCache: Map[Segments, (Int, Any)],
      watched: Seq[Watchable],
      scriptImportGraph: Map[os.Path, Seq[os.Path]],
      bootClassloader: java.net.URLClassLoader
  ): EvaluatorState = new EvaluatorState(
    workerCache,
    watched,
    scriptImportGraph,
    bootClassloader
  )
}
