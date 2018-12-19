package mill.scalalib

import coursier.Cache
import coursier.maven.MavenRepository
import mill.Agg
import mill.T
import mill.api.KeyedLockedCache
import mill.define.{Discover, Worker}
import mill.scalalib.Lib.resolveDependencies
import mill.scalalib.api.Util.isDotty
import mill.scalalib.api.ZincWorkerApi
import mill.api.Loose
import mill.util.JsonFormatters._

object ZincWorkerModule extends mill.define.ExternalModule with ZincWorkerModule{
  lazy val millDiscover = Discover[this.type]
}
trait ZincWorkerModule extends mill.Module{
  def repositories = Seq(
    Cache.ivy2Local,
    MavenRepository("https://repo1.maven.org/maven2"),
    MavenRepository("https://oss.sonatype.org/content/repositories/releases")
  )

  def classpath = T{
    mill.modules.Util.millProjectModule("MILL_SCALA_WORKER", "mill-scalalib-worker", repositories)
  }

  def scalalibClasspath = T{
    mill.modules.Util.millProjectModule("MILL_SCALA_LIB", "mill-scalalib", repositories)
  }

  def backgroundWrapperClasspath = T{
    mill.modules.Util.millProjectModule(
      "MILL_BACKGROUNDWRAPPER", "mill-scalalib-backgroundwrapper",
      repositories, artifactSuffix = ""
    )
  }

  def worker: Worker[mill.scalalib.api.ZincWorkerApi] = T.worker{
    val cl = mill.api.ClassLoader.create(
      classpath().map(_.path.toNIO.toUri.toURL).toVector,
      getClass.getClassLoader
    )
    val cls = cl.loadClass("mill.scalalib.worker.ZincWorkerImpl")
    val instance = cls.getConstructor(
      classOf[
        Either[
          (ZincWorkerApi.Ctx, Array[os.Path], (String, String) => os.Path),
          String => os.Path
        ]
      ],
      classOf[(Agg[os.Path], String) => os.Path],
      classOf[(Agg[os.Path], String) => os.Path],
      classOf[KeyedLockedCache[_]]
    )
      .newInstance(
        Left((
          T.ctx(),
          compilerInterfaceClasspath().map(_.path).toArray,
          scalaCompilerBridgeSourceJar(_, _).asSuccess.get.value
        )),
        mill.scalalib.api.Util.grepJar(_, "scala-library", _, sources = false),
        mill.scalalib.api.Util.grepJar(_, "scala-compiler", _, sources = false),
        new KeyedLockedCache.RandomBoundedCache(1, 1)
      )
    instance.asInstanceOf[mill.scalalib.api.ZincWorkerApi]
  }

  private val Milestone213 = raw"""2.13.(\d+)-M(\d+)""".r
  def scalaCompilerBridgeSourceJar(scalaVersion: String,
                                   scalaOrganization: String) = {
    val (scalaVersion0, scalaBinaryVersion0) = scalaVersion match {
      case Milestone213(_, _) => ("2.13.0-M2", "2.13.0-M2")
      case _ => (scalaVersion, mill.scalalib.api.Util.scalaBinaryVersion(scalaVersion))
    }

    val (bridgeDep, bridgeName, bridgeVersion) =
      if (isDotty(scalaVersion0)) {
        val org = scalaOrganization
        val name = "dotty-sbt-bridge"
        val version = scalaVersion
        (ivy"$org:$name:$version", name, version)
      } else {
        val org = "org.scala-sbt"
        val name = "compiler-bridge"
        val version = Versions.zinc
        (ivy"$org::$name:$version", s"${name}_$scalaBinaryVersion0", version)
      }

    resolveDependencies(
      repositories,
      Lib.depToDependency(_, scalaVersion0, ""),
      Seq(bridgeDep),
      sources = true
    ).map(deps =>
      mill.scalalib.api.Util.grepJar(deps.map(_.path), bridgeName, bridgeVersion, sources = true)
    )
  }

  def compilerInterfaceClasspath = T{
    resolveDependencies(
      repositories,
      Lib.depToDependency(_, "2.12.4", ""),
      Seq(ivy"org.scala-sbt:compiler-interface:${Versions.zinc}")
    )
  }

}
