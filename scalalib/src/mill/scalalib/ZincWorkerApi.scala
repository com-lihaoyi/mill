package mill.scalalib


import ammonite.ops.Path
import coursier.Cache
import coursier.maven.MavenRepository
import mill.Agg
import mill.T
import mill.define.{Discover, Worker}
import mill.scalalib.Lib.resolveDependencies
import mill.util.Loose
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

  def worker: Worker[ZincWorkerApi] = T.worker{
    val cl = mill.util.ClassLoader.create(
      classpath().map(_.path.toNIO.toUri.toURL).toVector,
      getClass.getClassLoader
    )
    val cls = cl.loadClass("mill.scalalib.worker.ZincWorkerImpl")
    val instance = cls.getConstructor(classOf[mill.util.Ctx], classOf[Array[String]])
      .newInstance(T.ctx(), compilerInterfaceClasspath().map(_.path.toString).toArray[String])
    instance.asInstanceOf[ZincWorkerApi]
  }

  def compilerInterfaceClasspath = T{
    resolveDependencies(
      repositories,
      Lib.depToDependency(_, "2.12.4", ""),
      Seq(ivy"org.scala-sbt:compiler-interface:${Versions.zinc}")
    )
  }

}

trait ZincWorkerApi {
  /** Compile a Java-only project */
  def compileJava(upstreamCompileOutput: Seq[CompilationResult],
                  sources: Agg[Path],
                  compileClasspath: Agg[Path],
                  javacOptions: Seq[String])
                 (implicit ctx: mill.util.Ctx): mill.eval.Result[CompilationResult]

  /** Compile a mixed Scala/Java or Scala-only project */
  def compileMixed(upstreamCompileOutput: Seq[CompilationResult],
                   sources: Agg[Path],
                   compileClasspath: Agg[Path],
                   javacOptions: Seq[String],
                   scalaVersion: String,
                   scalacOptions: Seq[String],
                   compilerBridgeSources: Path,
                   compilerClasspath: Agg[Path],
                   scalacPluginClasspath: Agg[Path])
                  (implicit ctx: mill.util.Ctx): mill.eval.Result[CompilationResult]

  def discoverMainClasses(compilationResult: CompilationResult)
                         (implicit ctx: mill.util.Ctx): Seq[String]

  def docJar(args: Seq[String]): Boolean
}
