package mill.playlib

import mill.api.PathRef
import mill.util.Util.millProjectModule
import mill.playlib.api.RouteCompilerType
import mill.scalalib._
import mill.scalalib.api._
import mill.{Agg, T, Task}

trait RouterModule extends ScalaModule with Version {

  def routes: T[Seq[PathRef]] = Task.Sources { millSourcePath / "routes" }

  def routeFiles = Task {
    val paths = routes().flatMap(file => os.walk(file.path))
    val routeFiles = paths.filter(_.ext == "routes") ++ paths.filter(_.last == "routes")
    routeFiles.map(f => PathRef(f))
  }

  /**
   * A [[Seq]] of additional imports to be added to the routes file.
   * Defaults to :
   *
   * - controllers.Assets.Asset
   * - play.libs.F
   */
  def routesAdditionalImport: Seq[String] = Seq(
    "controllers.Assets.Asset",
    "play.libs.F"
  )

  def generateForwardsRouter: Boolean = true

  def generateReverseRouter: Boolean = true

  def namespaceReverseRouter: Boolean = false

  /**
   * The routes compiler type to be used.
   *
   * Can only be one of:
   *
   * - [[RouteCompilerType.InjectedGenerator]]
   * - [[RouteCompilerType.StaticGenerator]]
   */
  def generatorType: RouteCompilerType = RouteCompilerType.InjectedGenerator

  def routerClasspath: T[Agg[PathRef]] = Task {
    defaultResolver().resolveDeps(
      playMinorVersion() match {
        case "2.6" | "2.7" | "2.8" =>
          Agg(ivy"com.typesafe.play::play-routes-compiler:${playVersion()}")
        case "2.9" =>
          Agg(ivy"com.typesafe.play::play-routes-compiler:${playVersion()}")
        case _ =>
          Agg(ivy"org.playframework::play-routes-compiler:${playVersion()}")
      }
    )
  }

  protected val routeCompilerWorker: RouteCompilerWorkerModule = RouteCompilerWorkerModule

  def compileRouter: T[CompilationResult] = Task(persistent = true) {
    T.log.debug(s"compiling play routes with ${playVersion()} worker")
    routeCompilerWorker.routeCompilerWorker().compile(
      routerClasspath = playRouterToolsClasspath(),
      files = routeFiles().map(_.path),
      additionalImports = routesAdditionalImport,
      forwardsRouter = generateForwardsRouter,
      reverseRouter = generateReverseRouter,
      namespaceReverseRouter = namespaceReverseRouter,
      generatorType = generatorType,
      dest = T.dest
    )
  }

  def playRouteCompilerWorkerClasspath = Task {
    millProjectModule(
      s"mill-contrib-playlib-worker-${playMinorVersion()}",
      repositoriesTask(),
      artifactSuffix = playMinorVersion() match {
        case "2.6" => "_2.12"
        case "2.7" | "2.8" => "_2.13"
        case _ => "_2.13"
      }
    )
  }

  def playRouterToolsClasspath = Task {
    playRouteCompilerWorkerClasspath() ++ routerClasspath()
  }

  def routerClasses = Task {
    Seq(compileRouter().classes)
  }

  override def generatedSources = Task {
    super.generatedSources() ++ routerClasses()
  }
}
