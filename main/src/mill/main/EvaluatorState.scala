package mill.main

import scala.collection.mutable

import mill.define.{ScriptNode, Segments}

class EvaluatorState private[main] (
    val rootModule: mill.define.BaseModule,
    val classLoaderSig: Seq[(Either[String, java.net.URL], Long)],
    val workerCache: mutable.Map[Segments, (Int, Any)],
    val watched: Seq[(ammonite.interp.Watchable, Long)],
    val setSystemProperties: Set[String],
    val importTree: Seq[ScriptNode]
) extends Product with Serializable {
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
  protected[this] def this(
      rootModule: mill.define.BaseModule,
      classLoaderSig: Seq[(Either[String, java.net.URL], Long)],
      workerCache: mutable.Map[Segments, (Int, Any)],
      watched: Seq[(ammonite.interp.Watchable, Long)],
      setSystemProperties: Set[String]
  ) = this(
    rootModule,
    classLoaderSig,
    workerCache,
    watched,
    setSystemProperties,
    Seq.empty
  )
  @deprecated(since = "0.10.0")
  def canEqual(that: Any): Boolean = ???
  @deprecated(since = "0.10.0")
  def productArity: Int = 6
  @deprecated(since = "0.10.0")
  def productElement(n: Int): Any = n match {
    case 0 => rootModule
    case 1 => classLoaderSig
    case 2 => workerCache
    case 3 => watched
    case 4 => setSystemProperties
    case 5 => importTree
    case _ => throw new IndexOutOfBoundsException(n.toString)
  }
  @deprecated(since = "0.10.0")
  protected[this] def copy(
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
object EvaluatorState extends runtime.AbstractFunction5[mill.define.BaseModule, Seq[(
        Either[String, java.net.URL],
        Long
    )], mutable.Map[Segments, (Int, Any)], Seq[(
        ammonite.interp.Watchable,
        Long
    )], Set[String], EvaluatorState] {

  @deprecated(since = "0.10.0")
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
  @deprecated(since = "0.10.0")
  def unapply(evaluatorState: EvaluatorState): Option[(mill.define.BaseModule, Seq[(
        Either[String, java.net.URL],
        Long
    )], mutable.Map[Segments, (Int, Any)], Seq[(
        ammonite.interp.Watchable,
        Long
    )], Set[String])] =
    Option((
      evaluatorState.rootModule,
      evaluatorState.classLoaderSig,
      evaluatorState.workerCache,
      evaluatorState.watched,
      evaluatorState.setSystemProperties
    ))
}
