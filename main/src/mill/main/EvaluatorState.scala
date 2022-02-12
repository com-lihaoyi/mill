package mill.main

import scala.collection.mutable

import mill.define.Segments

// TODO: Remove extends Product with Serializable before 0.11.0
class EvaluatorState @deprecated(message = "Use apply instead", since = "0.10.1") private[main] (
    _rootModule: mill.define.BaseModule,
    _classLoaderSig: Seq[(Either[String, java.net.URL], Long)],
    _workerCache: mutable.Map[Segments, (Int, Any)],
    _watched: Seq[(ammonite.interp.Watchable, Long)],
    _setSystemProperties: Set[String]
) extends Product with Serializable {

  def rootModule: mill.define.BaseModule = _rootModule
  def classLoaderSig: Seq[(Either[String, java.net.URL], Long)] = _classLoaderSig
  def workerCache: mutable.Map[Segments, (Int, Any)] = _workerCache
  def watched: Seq[(ammonite.interp.Watchable, Long)] = _watched
  def setSystemProperties: Set[String] = _setSystemProperties

  override def toString(): String = {
    s"""EvaluatorState(
       |  rootModule = $rootModule,
       |  classLoaderSig = $classLoaderSig,
       |  workerCache = $workerCache,
       |  watched = $watched,
       |  setSystemProperties = $setSystemProperties
       |)""".stripMargin
  }
  @deprecated(message = "Binary compatibility shim. To be removed", since = "0.10.1")
  def canEqual(that: Any): Boolean = that.isInstanceOf[EvaluatorState]
  @deprecated(message = "Binary compatibility shim. To be removed", since = "0.10.1")
  def productArity: Int = 6
  @deprecated(message = "Binary compatibility shim. To be removed", since = "0.10.1")
  def productElement(n: Int): Any = n match {
    case 0 => rootModule
    case 1 => classLoaderSig
    case 2 => workerCache
    case 3 => watched
    case 4 => setSystemProperties
    case _ => throw new IndexOutOfBoundsException(n.toString)
  }
  @deprecated("Construct a new instance with apply instead", since = "0.10.1")
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
    setSystemProperties
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
      setSystemProperties: Set[String]
  ): EvaluatorState = new EvaluatorState(
    rootModule,
    classLoaderSig,
    workerCache,
    watched,
    setSystemProperties
  )

  @deprecated(message = "Pattern matching not supported with EvaluatorState", since = "0.10.1")
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
