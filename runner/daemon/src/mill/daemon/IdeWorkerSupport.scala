package mill.daemon

import coursier.core.Repository
import coursier.{Dependency, Module, ModuleName, Organization, VersionConstraint}
import mill.api.daemon.internal.EvaluatorApi
import mill.api.daemon.internal.bsp.{BspBootstrapBridge, BspServerHandle}
import mill.api.{Logger, MillException, Result, SystemStreams}
import mill.javalib.api.JvmWorkerUtil
import mill.util.{BuildInfo, Jvm}

private object IdeWorkerSupport {
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
      startBspServer: java.lang.reflect.Method
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
      classOf[Logger],
      classOf[os.Path],
      java.lang.Long.TYPE,
      classOf[Boolean],
      classOf[Boolean],
      classOf[Boolean],
      classOf[BspBootstrapBridge]
    )

    BspHandles(classLoader, startBspServer)
  }

  def startBspServer(
      topLevelBuildRoot: os.Path,
      streams: SystemStreams,
      logDir: os.Path,
      canReload: Boolean,
      baseLogger: Logger,
      out: os.Path,
      sessionProcessPid: Long,
      noWaitForBspLock: Boolean,
      killOther: Boolean,
      bspWatch: Boolean,
      bootstrapBridge: BspBootstrapBridge
  ): BspServerHandle = {
    val handles = bspHandles
    val result = mill.api.daemon.ClassLoader.withContextClassLoader(handles.classLoader) {
      unwrapInvocation(handles.startBspServer.invoke(
        null,
        topLevelBuildRoot,
        streams,
        logDir,
        java.lang.Boolean.valueOf(canReload),
        baseLogger,
        out,
        java.lang.Long.valueOf(sessionProcessPid),
        noWaitForBspLock,
        killOther,
        java.lang.Boolean.valueOf(bspWatch),
        bootstrapBridge
      )).asInstanceOf[Result[Any]]
    }

    result match {
      case Result.Success(handle: BspServerHandle) => handle
      case Result.Success(_) =>
        throw new MillException("BSP worker returned an unexpected payload")
      case failure: Result.Failure =>
        throw new MillException(failure.error)
    }
  }
}
