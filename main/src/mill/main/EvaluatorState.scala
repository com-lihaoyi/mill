package mill.main

import scala.collection.mutable

import mill.define.{ScriptNode, Segments}

// TODO: Remove extends Product with Serializable before 0.11.0
class EvaluatorState private (
    _rootModule: mill.define.BaseModule,
    _classLoaderSig: Seq[(Either[String, java.net.URL], Long)],
    _workerCache: mutable.Map[Segments, (Int, Any)],
    _watched: Seq[(ammonite.interp.Watchable, Long)],
    _setSystemProperties: Set[String],
    _importTree: Seq[ScriptNode]
) extends Product with Serializable {

  @deprecated(message = "Use apply instead", since = "mill 0.10.1")
  private[main] def this(
      _rootModule: mill.define.BaseModule,
      _classLoaderSig: Seq[(Either[String, java.net.URL], Long)],
      _workerCache: mutable.Map[Segments, (Int, Any)],
      _watched: Seq[(ammonite.interp.Watchable, Long)],
      _setSystemProperties: Set[String]
  ) = this(
    _rootModule,
    _classLoaderSig,
    _workerCache,
    _watched,
    _setSystemProperties,
    Seq.empty
  )

  def rootModule: mill.define.BaseModule = _rootModule
  def classLoaderSig: Seq[(Either[String, java.net.URL], Long)] = _classLoaderSig
  def workerCache: mutable.Map[Segments, (Int, Any)] = _workerCache
  def watched: Seq[(ammonite.interp.Watchable, Long)] = _watched
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
  @deprecated(message = "Binary compatibility shim. To be removed", since = "mill 0.10.1")
  def canEqual(that: Any): Boolean = that.isInstanceOf[EvaluatorState]
  @deprecated(message = "Binary compatibility shim. To be removed", since = "mill 0.10.1")
  def productArity: Int = 6
  @deprecated(message = "Binary compatibility shim. To be removed", since = "mill 0.10.1")
  def productElement(n: Int): Any = n match {
    case 0 => rootModule
    case 1 => classLoaderSig
    case 2 => workerCache
    case 3 => watched
    case 4 => setSystemProperties
    case _ => throw new IndexOutOfBoundsException(n.toString)
  }
  @deprecated("Construct a new instance with apply instead", since = "mill 0.10.1")
  def copy(
      rootModule: mill.define.BaseModule = this.rootModule,
      classLoaderSig: Seq[(Either[String, java.net.URL], Long)] = this.classLoaderSig,
      workerCache: mutable.Map[Segments, (Int, Any)] = this.workerCache,
      watched: Seq[(ammonite.interp.Watchable, Long)] = this.watched,
      setSystemProperties: Set[String] = this.setSystemProperties
  ): EvaluatorState = new EvaluatorState(
    rootModule,
    classLoaderSig,
    workerCache,
    watched,
    setSystemProperties,
    this.importTree
  )
}
// TODO: Remove the extends runtime.AbstractFunction5[...] before 0.11.0
object EvaluatorState extends runtime.AbstractFunction5[mill.define.BaseModule, Seq[(
        Either[String, java.net.URL],
        Long
    )], mutable.Map[Segments, (Int, Any)], Seq[(
        ammonite.interp.Watchable,
        Long
    )], Set[String], EvaluatorState] {

  def apply(
      rootModule: mill.define.BaseModule,
      classLoaderSig: Seq[(Either[String, java.net.URL], Long)],
      workerCache: mutable.Map[Segments, (Int, Any)],
      watched: Seq[(ammonite.interp.Watchable, Long)],
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

  @deprecated(message = "Use other apply instead", since = "mill 0.10.1")
  def apply(
      rootModule: mill.define.BaseModule,
      classLoaderSig: Seq[(Either[String, java.net.URL], Long)],
      workerCache: mutable.Map[Segments, (Int, Any)],
      watched: Seq[(ammonite.interp.Watchable, Long)],
      setSystemProperties: Set[String]
  ): EvaluatorState = new EvaluatorState(
    rootModule,
    classLoaderSig,
    workerCache,
    watched,
    setSystemProperties,
    Seq.empty
  )

  @deprecated(message = "Pattern matching not supported with EvaluatorState", since = "mill 0.10.1")
  def unapply(evaluatorState: EvaluatorState): Option[(
      mill.define.BaseModule,
      Seq[(
          Either[String, java.net.URL],
          Long
      )],
      mutable.Map[Segments, (Int, Any)],
      Seq[(
          ammonite.interp.Watchable,
          Long
      )],
      Set[String]
  )] =
    Some((
      evaluatorState.rootModule,
      evaluatorState.classLoaderSig,
      evaluatorState.workerCache,
      evaluatorState.watched,
      evaluatorState.setSystemProperties
    ))
}
