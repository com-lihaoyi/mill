package mill
package playlib

import coursier.{Cache, MavenRepository}
import mill.api.Loose
import mill.playlib.api.RouteCompilerType
import mill.scalalib.Lib.resolveDependencies
import mill.scalalib._
import mill.scalalib.api._
import os.Path

trait RouterModule extends mill.Module with ScalaModule {

  /**
    * Defines the version of playframework to be used to compile this projects
    * routes.
    */
  def playVersion: T[String]

  override def generatedSources = T {
    super.generatedSources() ++ Seq(compileRouter().classes)
  }

  /**
    * The [[PathRef]] to the main routes file.
    *
    * This is the default path for play projects and it should be fine but you
    * can override it if needed.
    */
  def routesDirectory = T.sources {
    millSourcePath / "conf"
  }

  private def routesFiles = T {
    val files = routesDirectory()
    locateFilesBy(files, _.last.endsWith(".routes")) ++ locateFilesBy(files, _.last == "routes")
  }

  private def locateFilesBy(files: Seq[PathRef], p: Path => Boolean) = {
    files.flatMap(file => {
      os.walk(file.path).filter(p).map(f => PathRef(f))
    })
  }


  /**
    * A [[Seq]] of additional imports to be added to the routes file.
    * Defaults to :
    * <ul>
    * <li> controllers.Assets.Asset
    * <li> play.libs.F
    * </ul>
    */
  def routesAdditionalImport: Seq[String] = Seq(
    "controllers.Assets.Asset",
    "play.libs.F"
  )

  def generateForwardsRouter: Boolean = true

  def generateReverseRouter: Boolean = true

  def namespaceReverseRouter: Boolean = false

  /**
    * The routes compiler type to be used. Can only be one of:
    * <ul>
    * <li>[[RouteCompilerType.InjectedGenerator]]
    * <li>[[RouteCompilerType.StaticGenerator]]
    * </ul>
    *
    * @return
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
      routesFiles().map(_.path),
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