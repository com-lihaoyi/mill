package mill.daemon

import mill.api.Val
import mill.api.JsonFormatters.*
import mill.api.MillURLClassLoader
import mill.api.daemon.internal.{EvaluatorApi, PathRefApi, TaskApi}
import mill.api.daemon.Watchable
import mill.api.daemon.internal.LauncherLocking
import upickle.{ReadWriter, macroRW}

/**
 * Per-launcher view of the current bootstrap/evaluation run.
 *
 * This keeps the launcher-owned resources that cannot be shared daemon-wide:
 * active evaluators, retained meta-build read leases, final-task watches,
 * and the launcher session/artifact handles.
 */
case class RunnerLauncherState(
    errorOpt: Option[String] = None,
    buildFile: Option[String] = None,
    bootstrapEvalWatched: Option[Watchable] = None,
    frames: List[RunnerLauncherState.LauncherFrame] = Nil,
    sessionOpt: Option[LauncherSession] = None
) extends Watching.Result
    with AutoCloseable {
  import RunnerLauncherState.*

  def withFrame(frame: LauncherFrame): RunnerLauncherState =
    copy(frames = frame +: frames)

  def frameAt(depth: Int): Option[LauncherFrame] = frames.find(_.depth == depth)

  def finalFrame: Option[LauncherFrame] = frames.collectFirst {
    case f if f.role.isInstanceOf[Role.Final] => f
  }

  def withError(err: String): RunnerLauncherState = copy(errorOpt = Some(err))

  def withSession(s: LauncherSession): RunnerLauncherState =
    copy(sessionOpt = Some(s))

  def processedDepths: Int = frames.size

  def moduleWatchedAt(depth: Int): Option[Seq[Watchable]] =
    frameAt(depth).map(_.moduleWatched)

  def finalModuleWatchedAt(depth: Int): Option[Seq[Watchable]] =
    finalFrame.collect { case f if f.depth == depth => f.moduleWatched }

  def watched: Seq[Watchable] =
    frames.flatMap(f => f.evalWatched ++ f.moduleWatched) ++ bootstrapEvalWatched.toSeq

  def allEvaluators: Seq[EvaluatorApi] = frames.map(_.evaluator)

  override def close(): Unit = {
    val leases = frames.flatMap(_.role match {
      case m: Role.Meta => m.readLease
      case _ => None
    })
    closeAll(allEvaluators.distinct ++ leases ++ sessionOpt.toSeq)
  }

  private def closeAll(resources: Iterable[AutoCloseable]): Unit = {
    var firstError: Throwable = null
    for (resource <- resources) {
      try resource.close()
      catch {
        case e: Throwable =>
          if (firstError == null) firstError = e
          else firstError.addSuppressed(e)
      }
    }
    if (firstError != null) throw firstError
  }
}

object RunnerLauncherState {
  def empty: RunnerLauncherState = RunnerLauncherState()

  case class WorkerInfo(identityHashCode: Int, inputHash: Int)
  implicit val workerInfoRw: ReadWriter[WorkerInfo] = macroRW

  def summarizeWorkerCache(
      workerCache: collection.Map[String, (Int, Val, TaskApi[?])]
  ): Map[String, WorkerInfo] = workerCache.synchronized {
    workerCache.iterator.map { case (k, (i, v, _)) =>
      (k, WorkerInfo(System.identityHashCode(v), i))
    }.toMap
  }

  /**
   * Simplified representation of frame data, written to disk for
   * debugging and testing purposes.
   */
  case class Logged(
      workerCache: Map[String, WorkerInfo],
      evalWatched: Seq[os.Path],
      moduleWatched: Seq[os.Path],
      classLoaderIdentity: Option[Int],
      runClasspath: Seq[(os.Path, Int)],
      runClasspathHash: Int
  )
  implicit val loggedRw: ReadWriter[Logged] = macroRW

  private[daemon] def buildLogged(
      workerCache: Map[String, WorkerInfo],
      evalWatched: Seq[Watchable],
      moduleWatched: Seq[Watchable],
      classLoaderIdentity: Option[Int],
      runClasspath: Seq[PathRefApi]
  ): Logged = {
    def paths(ws: Seq[Watchable]) =
      ws.collect { case Watchable.Path(p, _, _) => os.Path(p) }.distinct
    Logged(
      workerCache,
      paths(evalWatched),
      paths(moduleWatched),
      classLoaderIdentity,
      runClasspath.map(p => os.Path(p.javaPath) -> p.sig),
      runClasspath.hashCode()
    )
  }

  enum Role {
    case Meta(
        classLoaderOpt: Option[MillURLClassLoader],
        runClasspath: Seq[PathRefApi],
        readLease: Option[LauncherLocking.Lease],
        spanningInvalidationTree: Option[String]
    )
    case Final(tasksAndParams: Seq[String])
  }

  /**
   * One level of an in-progress launcher evaluation.
   *
   * For meta-build levels, [[Role.Meta]] carries the classloader/classpath the
   * launcher published, the read lease that keeps the shared frame stable, and
   * the spanning invalidation tree captured during a fresh evaluation. For the
   * user-task evaluation, [[Role.Final]] carries the task selectors that were
   * run, used to short-circuit a re-run when nothing has changed.
   */
  case class LauncherFrame(
      depth: Int,
      evaluator: EvaluatorApi,
      evalWatched: Seq[Watchable],
      moduleWatched: Seq[Watchable],
      role: Role
  ) {
    def classLoaderOpt: Option[MillURLClassLoader] = role match {
      case m: Role.Meta => m.classLoaderOpt
      case _: Role.Final => None
    }

    def runClasspath: Seq[PathRefApi] = role match {
      case m: Role.Meta => m.runClasspath
      case _: Role.Final => Nil
    }

    def logged: Logged = {
      val workerCache = role match {
        case _: Role.Meta => summarizeWorkerCache(evaluator.workerCache)
        case _: Role.Final => Map.empty[String, WorkerInfo]
      }
      buildLogged(
        workerCache,
        evalWatched,
        moduleWatched,
        classLoaderOpt.map(_.identity),
        runClasspath
      )
    }
  }

  def metaFrame(
      depth: Int,
      evaluator: EvaluatorApi,
      sharedFrame: RunnerSharedState.Frame.Reusable,
      lease: LauncherLocking.Lease,
      spanningInvalidationTree: Option[String]
  ): LauncherFrame =
    LauncherFrame(
      depth = depth,
      evaluator = evaluator,
      evalWatched = sharedFrame.evalWatched,
      moduleWatched = sharedFrame.moduleWatched,
      role = Role.Meta(
        classLoaderOpt = Some(sharedFrame.classLoader),
        runClasspath = sharedFrame.runClasspath,
        readLease = Some(lease),
        spanningInvalidationTree = spanningInvalidationTree
      )
    )

  def failedMetaFrame(
      depth: Int,
      evaluator: EvaluatorApi,
      evalWatched: Seq[Watchable],
      moduleWatched: Seq[Watchable]
  ): LauncherFrame =
    LauncherFrame(
      depth = depth,
      evaluator = evaluator,
      evalWatched = evalWatched,
      moduleWatched = moduleWatched,
      role = Role.Meta(
        classLoaderOpt = None,
        runClasspath = Nil,
        readLease = None,
        spanningInvalidationTree = None
      )
    )
}
