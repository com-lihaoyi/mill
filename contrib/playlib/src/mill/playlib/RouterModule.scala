package mill
package playlib

import coursier.{Cache, MavenRepository}
import mill.api.Loose
import mill.playlib.api.RouteCompilerType
import mill.scalalib.Lib.resolveDependencies
import mill.scalalib._
import mill.scalalib.api._

trait RouterModule extends mill.Module with ScalaModule {

  def playVersion: T[String]

  override def generatedSources = T {
    super.generatedSources() ++ Seq(compileRouter().classes)
  }

  def routes = T.sources { millSourcePath / 'routes }

  private def routeFiles = T {
    val paths = routes().flatMap(file => os.walk(file.path))
    val routeFiles=paths.filter(_.ext=="routes") ++ paths.filter(_.last == "routes")
    routeFiles.map(f=>PathRef(f))
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

  def routerClasspath: T[Loose.Agg[PathRef]] = T {
    resolveDependencies(
      Seq(
        Cache.ivy2Local,
        MavenRepository("https://repo1.maven.org/maven2")
      ),
      Lib.depToDependency(_, scalaVersion()),
      Seq(
        ivy"com.typesafe.play::routes-compiler:${playVersion()}"
      )
    )
  }

  final def compileRouter: T[CompilationResult] = T {
    T.ctx().log.debug(s"compiling play routes with ${playVersion()} worker")
    RouteCompilerWorkerModule.routeCompilerWorker().compile(
      toolsClasspath().map(_.path),
      routeFiles().map(_.path),
      routesAdditionalImport,
      generateForwardsRouter,
      generateReverseRouter,
      namespaceReverseRouter,
      generatorType,
      T.ctx().dest)
  }

  private def playMinorVersion: T[String] = T {
    playVersion().split("\\.").take(2).mkString("", ".", ".0")
  }

  private def playRouteCompilerWorkerClasspath = T {
    val workerKey = "MILL_CONTRIB_PLAYLIB_ROUTECOMPILER_WORKER_" + playMinorVersion().replace(".",
      "_")
    T.ctx.log.debug(s"classpath worker key: $workerKey")

    //While the following seems to work (tests pass), I am not completely
    //confident that the strings I used for artifact and resolveFilter are
    //actually correct
    mill.modules.Util.millProjectModule(
      workerKey,
      s"mill-contrib-playlib-worker-${playVersion()}",
      repositories,
      resolveFilter = _.toString.contains("mill-contrib-playlib-worker")
    )
  }

  private def toolsClasspath = T {
    playRouteCompilerWorkerClasspath() ++ routerClasspath()
  }
}