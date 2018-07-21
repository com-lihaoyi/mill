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

object ScalaWorkerModule extends mill.define.ExternalModule with ScalaWorkerModule{
  lazy val millDiscover = Discover[this.type]
}
trait ScalaWorkerModule extends mill.Module{
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

  def worker: Worker[ScalaWorkerApi] = T.worker{
    val cl = mill.util.ClassLoader.create(
      classpath().map(_.path.toNIO.toUri.toURL).toVector,
      getClass.getClassLoader
    )
    val cls = cl.loadClass("mill.scalalib.worker.ScalaWorker")
    val instance = cls.getConstructor(classOf[mill.util.Ctx], classOf[Array[String]])
      .newInstance(T.ctx(), compilerInterfaceClasspath().map(_.path.toString).toArray[String])
    instance.asInstanceOf[ScalaWorkerApi]
  }

  def compilerInterfaceClasspath = T{
    resolveDependencies(
      repositories,
      Lib.depToDependency(_, "2.12.4", ""),
      Seq(ivy"org.scala-sbt:compiler-interface:1.1.0")
    )
  }

}

trait ScalaWorkerApi {

  def compileScala(scalaVersion: String,
                   sources: Agg[Path],
                   compilerBridgeSources: Path,
                   compileClasspath: Agg[Path],
                   compilerClasspath: Agg[Path],
                   scalacOptions: Seq[String],
                   scalacPluginClasspath: Agg[Path],
                   javacOptions: Seq[String],
                   upstreamCompileOutput: Seq[CompilationResult])
                  (implicit ctx: mill.util.Ctx): mill.eval.Result[CompilationResult]


  def discoverMainClasses(compilationResult: CompilationResult)
                         (implicit ctx: mill.util.Ctx): Seq[String]
}
