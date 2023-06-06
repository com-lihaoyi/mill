package mill.playlib

import mill.api.PathRef
import mill.util.Util.millProjectModule
import mill.playlib.api.RouteCompilerType
import mill.scalalib._
import mill.scalalib.api._
import mill.{Agg, T}

trait RouterModule extends ScalaModule with Version {

  def routes: T[Seq[PathRef]] = T.sources { millSourcePath / "routes" }

  def routeFiles = T {
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

  def routerClasspath: T[Agg[PathRef]] = T {
    resolveDeps(T.task {
      val bind = bindDependency()
      Agg(ivy"com.typesafe.play::routes-compiler:${playVersion()}").map(bind)
    })()
  }

  protected val routeCompilerWorker: RouteCompilerWorkerModule = RouteCompilerWorkerModule

  def compileRouter: T[CompilationResult] = T.persistent {
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

  def playRouteCompilerWorkerClasspath = T {
    millProjectModule(
      s"mill-contrib-playlib-worker-${playMinorVersion()}",
      repositoriesTask(),
      artifactSuffix = playMinorVersion() match {
        case "2.6" => "_2.12"
        case _ => "_2.13"
      }
    )
  }

  def playRouterToolsClasspath = T {
    playRouteCompilerWorkerClasspath() ++ routerClasspath()
  }

  def routerClasses = T {
    Seq(compileRouter().classes)
  }

  override def generatedSources = T {
    super.generatedSources() ++ routerClasses()
  }
}
