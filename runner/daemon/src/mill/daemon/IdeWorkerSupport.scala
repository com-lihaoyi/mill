package mill.daemon

import coursier.core.Repository
import coursier.{Dependency, Module, ModuleName, Organization, VersionConstraint}
import mill.api.daemon.internal.{CompileProblemReporter, EvaluatorApi}
import mill.api.daemon.internal.bsp.BspServerHandle
import mill.api.{Logger, MillException, Result, SystemStreams}
import mill.javalib.api.JvmWorkerUtil
import mill.client.lock.Lock
import mill.util.{BuildInfo, Jvm}

private object IdeWorkerSupport {
  final case class BspBuildClient private[daemon] (private[daemon] val value: AnyRef)

  private val organization = Organization("com.lihaoyi")

  private val scalaBinaryVersion = JvmWorkerUtil.scalaBinaryVersion(BuildInfo.scalaVersion)

  private def projectDep(moduleName: String): Dependency =
    Dependency(
      Module(organization, ModuleName(s"${moduleName}_$scalaBinaryVersion")),
      VersionConstraint(BuildInfo.millVersion)
    )

  private def repositories(): Seq[Repository] =
    Jvm.reposFromStrings(mill.api.daemon.MillRepositories.get).get ++
      coursier.Resolve.defaultRepositories

  private def resolveClasspath(moduleName: String): Seq[os.Path] =
    Jvm
      .resolveDependencies(
        repositories = repositories(),
        deps = Seq(projectDep(moduleName)),
        force = Nil
      )
      .get
      .map(_.path)

  private def createClassLoader(moduleName: String): ClassLoader = {
    val classpath = resolveClasspath(moduleName)
    Jvm.createClassLoader(
      classPath = classpath,
      parent = getClass.getClassLoader,
      label = s"mill-daemon-lazy-${moduleName}"
    )
  }

  private def unwrapInvocation[T](f: => T): T =
    try f
    catch {
      case e: java.lang.reflect.InvocationTargetException if e.getCause != null =>
        throw e.getCause
    }

  private case class IdeaHandles(classLoader: ClassLoader, ctor: java.lang.reflect.Constructor[?])
  private lazy val ideaHandles = {
    val classLoader = createClassLoader("mill-runner-idea")
    val ctor = classLoader
      .loadClass("mill.idea.GenIdeaImpl")
      .getConstructor(classOf[scala.collection.immutable.Seq[?]])
    IdeaHandles(classLoader, ctor)
  }

  def runIdeaGeneration(evaluators: Seq[EvaluatorApi]): Unit = {
    val handles = ideaHandles
    mill.api.daemon.ClassLoader.withContextClassLoader(handles.classLoader) {
      val impl = unwrapInvocation(handles.ctor.newInstance(evaluators))
      val run = impl.getClass.getMethod("run")
      unwrapInvocation(run.invoke(impl))
      ()
    }
  }

  private case class BspHandles(
      classLoader: ClassLoader,
      startBspServer: java.lang.reflect.Method,
      bspEvaluatorsCtor: java.lang.reflect.Constructor[?],
      bspIdByModule: java.lang.reflect.Method,
      reporterPoolFactory: java.lang.reflect.Method,
      utilsModule: AnyRef
  )
  private lazy val bspHandles = {
    val classLoader = createClassLoader("mill-runner-bsp-worker")

    val workerClass = classLoader.loadClass("mill.bsp.worker.BspWorkerImpl")
    val startBspServer = workerClass.getMethod(
      "startBspServer",
      classOf[os.Path],
      classOf[SystemStreams],
      classOf[os.Path],
      java.lang.Boolean.TYPE,
      classOf[Lock],
      classOf[Logger],
      classOf[os.Path],
      classOf[os.Path]
    )

    val bspEvaluatorsClass = classLoader.loadClass("mill.bsp.worker.BspEvaluators")
    val bspEvaluatorsCtor = bspEvaluatorsClass.getConstructor(
      classOf[os.Path],
      classOf[scala.collection.immutable.Seq[?]],
      classOf[scala.Function1[?, ?]],
      classOf[scala.collection.immutable.Seq[?]]
    )
    val bspIdByModule = bspEvaluatorsClass.getMethod("bspIdByModule")

    val utilsModule = classLoader.loadClass("mill.bsp.worker.Utils$")
      .getField("MODULE$")
      .get(null)
      .asInstanceOf[AnyRef]
    val buildClientClass = classLoader.loadClass("ch.epfl.scala.bsp4j.BuildClient")
    val reporterPoolFactory = utilsModule.getClass.getMethod(
      "getBspLoggedReporterPool",
      classOf[String],
      classOf[scala.collection.immutable.Map[?, ?]],
      buildClientClass
    )

    BspHandles(
      classLoader,
      startBspServer,
      bspEvaluatorsCtor,
      bspIdByModule,
      reporterPoolFactory,
      utilsModule
    )
  }

  def startBspServer(
      topLevelBuildRoot: os.Path,
      streams: SystemStreams,
      logDir: os.Path,
      canReload: Boolean,
      outLock: Lock,
      baseLogger: Logger,
      out: os.Path,
      daemonDir: os.Path
  ): (BspServerHandle, BspBuildClient) = {
    val handles = bspHandles
    val result = mill.api.daemon.ClassLoader.withContextClassLoader(handles.classLoader) {
      unwrapInvocation(handles.startBspServer.invoke(
        null,
        topLevelBuildRoot,
        streams,
        logDir,
        java.lang.Boolean.valueOf(canReload),
        outLock,
        baseLogger,
        out,
        daemonDir
      )).asInstanceOf[Result[(Any, Any)]]
    }

    result match {
      case Result.Success((handle: BspServerHandle, buildClient: AnyRef)) =>
        (handle, BspBuildClient(buildClient))
      case Result.Success(_) =>
        throw new MillException("BSP worker returned an unexpected payload")
      case failure: Result.Failure =>
        throw new MillException(failure.error)
    }
  }

  def bspReporterPool(
      workspaceDir: os.Path,
      evaluators: Seq[EvaluatorApi],
      buildClient: BspBuildClient
  ): Int => Option[CompileProblemReporter] = {
    val handles = bspHandles
    val debug: (() => String) => Unit = _ => ()

    val bspEvaluators = mill.api.daemon.ClassLoader.withContextClassLoader(handles.classLoader) {
      handles.bspEvaluatorsCtor.newInstance(
        workspaceDir,
        evaluators,
        debug,
        Seq.empty[mill.api.daemon.Watchable]
      )
    }
    val bspIdByModule = handles.bspIdByModule.invoke(bspEvaluators)
    val rawPool = mill.api.daemon.ClassLoader.withContextClassLoader(handles.classLoader) {
      unwrapInvocation(
        handles.reporterPoolFactory
          .invoke(handles.utilsModule, "", bspIdByModule, buildClient.value)
          .asInstanceOf[Int => Option[?]]
      )
    }
    (moduleHashCode: Int) => rawPool(moduleHashCode).map(_.asInstanceOf[CompileProblemReporter])
  }
}
