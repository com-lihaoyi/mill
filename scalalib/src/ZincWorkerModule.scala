package mill.scalalib

import mill.Agg
import mill.T
import mill.api.{Ctx, FixSizedCache, KeyedLockedCache}
import mill.define.{Command, Discover, ExternalModule, Worker}
import mill.scalalib.Lib.resolveDependencies
import mill.scalalib.api.Util.{isBinaryBridgeAvailable, isDotty}
import mill.scalalib.api.ZincWorkerApi
import mill.util.JsonFormatters._

object ZincWorkerModule extends ExternalModule with ZincWorkerModule with CoursierModule {
  lazy val millDiscover = Discover[this.type]
}

trait ZincWorkerModule extends mill.Module with OfflineSupportModule { self: CoursierModule =>

  def classpath = T{
    mill.modules.Util.millProjectModule("MILL_SCALA_WORKER", "mill-scalalib-worker", repositories)
  }

  def scalalibClasspath = T{
    mill.modules.Util.millProjectModule(
      "MILL_SCALA_LIB",
      "mill-scalalib",
      repositories,
      artifactSuffix = "_2.13"
    )
  }

  def backgroundWrapperClasspath = T{
    mill.modules.Util.millProjectModule(
      "MILL_BACKGROUNDWRAPPER", "mill-scalalib-backgroundwrapper",
      repositories, artifactSuffix = ""
    )
  }

  def worker: Worker[mill.scalalib.api.ZincWorkerApi] = T.worker {
    val ctx = T.ctx()
    val jobs = T.ctx() match {
      case j: Ctx.Jobs => j.jobs
      case _ => 1
    }
    ctx.log.debug(s"ZinkWorker: using cache size ${jobs}")
    val cp = compilerInterfaceClasspath()
    val cl = mill.api.ClassLoader.create(
      classpath().map(_.path.toNIO.toUri.toURL).toVector,
      getClass.getClassLoader
    )
    val cls = cl.loadClass("mill.scalalib.worker.ZincWorkerImpl")
    val instance = cls.getConstructor(
      classOf[
        Either[
          (ZincWorkerApi.Ctx, (String, String) => (Option[Array[os.Path]], os.Path)),
          String => os.Path
        ]
      ],
      classOf[(Agg[os.Path], String) => os.Path],
      classOf[(Agg[os.Path], String) => os.Path],
      classOf[KeyedLockedCache[_]],
      classOf[Boolean]
    )
      .newInstance(
        Left((
          T.ctx(),
          (x: String, y: String) => scalaCompilerBridgeJar(x, y, cp).asSuccess.get.value
        )),
        mill.scalalib.api.Util.grepJar(_, "scala-library", _, sources = false),
        mill.scalalib.api.Util.grepJar(_, "scala-compiler", _, sources = false),
        new FixSizedCache(jobs),
        false.asInstanceOf[AnyRef]
      )
    instance.asInstanceOf[mill.scalalib.api.ZincWorkerApi]
  }

  def scalaCompilerBridgeJar(scalaVersion: String,
                             scalaOrganization: String,
                             compileClassPath: Agg[mill.api.PathRef]) = {
    val (scalaVersion0, scalaBinaryVersion0) = scalaVersion match {
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
    val useSources = !isBinaryBridgeAvailable(scalaVersion)

    resolveDependencies(
      repositories,
      Lib.depToDependency(_, scalaVersion0),
      Seq(bridgeDep),
      useSources
    ).map{deps =>
      val cp = if (useSources) Some(compileClassPath.map(_.path).toArray) else None
      val res = mill.scalalib.api.Util.grepJar(deps.map(_.path), bridgeName, bridgeVersion, useSources)
      (cp, res)
    }
  }

  def compilerInterfaceClasspath = T{
    resolveDependencies(
      repositories,
      Lib.depToDependency(_, "2.12.4", ""),
      Seq(ivy"org.scala-sbt:compiler-interface:${Versions.zinc}"),
      ctx = Some(implicitly[mill.util.Ctx.Log])
    )
  }

  override def prepareOffline(): Command[Unit] = T.command {
    super.prepareOffline()
    classpath()
    compilerInterfaceClasspath()
    // worker()
    ()
  }

  def prepareOfflineCompiler(scalaVersion: String, scalaOrganization: String): Command[Unit] = T.command {
    classpath()
    val cp = compilerInterfaceClasspath()
    scalaCompilerBridgeJar(scalaVersion, scalaOrganization, cp)
    ()
  }

}
