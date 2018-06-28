package mill
package playlib

import coursier.{Cache, MavenRepository}
import mill.scalalib.Lib.resolveDependencies
import mill.scalalib._
import mill.util.Loose

trait RouterModule extends mill.Module {

  def playVersion: T[String]

  def routesFile: T[PathRef] = T {
    val routesPath = millSourcePath / "conf" / "routes"
    PathRef(routesPath)
  }

  def routerClasspath: T[Loose.Agg[PathRef]] = T {
    resolveDependencies(
      Seq(
        Cache.ivy2Local,
        MavenRepository("https://repo1.maven.org/maven2")
      ),
      Lib.depToDependency(_, "2.12.4"),
      Seq(
        ivy"com.typesafe.play::routes-compiler:${playVersion()}"
      )
    )
  }

  def routesAdditionalImport: Seq[String] = Seq(
    "controllers.Assets.Asset",
    "play.libs.F"
  )

  def generateForwardsRouter: Boolean = true

  def generateReverseRouter: Boolean = true

  def namespaceReverseRouter: Boolean = false

  def compileRouter: T[CompilationResult] = T.persistent {
    RouterGeneratorWorkerApi.routerGeneratorWorker
      .compile(routerClasspath().map(_.path),
        routesFile().path,
        routesAdditionalImport,
        generateForwardsRouter,
        generateReverseRouter,
        namespaceReverseRouter,
        T.ctx().dest)
  }
}