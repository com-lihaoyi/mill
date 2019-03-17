package mill
package contrib
package scoverage

import coursier.{Cache, MavenRepository}
import mill.api.Loose
import mill.eval.PathRef
import mill.util.Ctx
import mill.scalalib.{DepSyntax, JavaModule, Lib, ScalaModule, TestModule}

// import _root_.scoverage.Serializer.{ coverageFile, deserialize }
// import _root_.scoverage.IOUtils.{ findMeasurementFiles, invoked }
// import _root_.scoverage.report.ScoverageHtmlWriter

trait ScoverageModule extends ScalaModule { outer: ScalaModule =>
  def scoverageVersion: T[String]

  def scoverageClasspath: T[Loose.Agg[PathRef]] = T {
    Lib.resolveDependencies(
      Seq(Cache.ivy2Local, MavenRepository("https://repo1.maven.org/maven2")),
      Lib.depToDependency(_, outer.scalaVersion()),
      Seq(ivy"org.scoverage::scalac-scoverage-plugin:${scoverageVersion}"),
      ctx = Some(implicitly[mill.util.Ctx.Log])
    )
  }

  object scoverage extends ScalaModule {
    private def selfDir = T { T.ctx().dest / os.up / os.up }
    private def dataDir = T { selfDir() / "data" }

    def sources = outer.sources
    def resources = outer.resources
    def scalaVersion = outer.scalaVersion()
    def compileIvyDeps = outer.compileIvyDeps()
    def ivyDeps = outer.ivyDeps() ++
      Agg(ivy"org.scoverage::scalac-scoverage-runtime:${outer.scoverageVersion()}")
    def scalacPluginIvyDeps = outer.scalacPluginIvyDeps() ++
      Agg(ivy"org.scoverage::scalac-scoverage-plugin:${outer.scoverageVersion()}")
    def scalacOptions = outer.scalacOptions() ++
      Seq(s"-P:scoverage:dataDir:$dataDir")

    // def htmlReport() = T.command {
    //   val coverageFileObj = coverageFile(dataDir)
    //   val coverage = deserialize(coverageFileObj)
    //   coverage(invoked(findMeasurementFiles(dataDir)))
    //   val Seq(PathRef(sourceFolderPath, _, _)) = sources()
    //   val sourceFolders = Seq(sourceFolderPath.toIO)
    //   val htmlFolder = new java.io.File(s"${selfDir}/htmlReport")
    //   htmlFolder.mkdir()
    //   new ScoverageHtmlWriter(sourceFolders, htmlFolder, None)
    //     .write(coverage)
    // }
  }

  trait ScoverageTests { inner: TestModule with ScalaModule =>
    override def moduleDeps: Seq[JavaModule] = Seq(outer.scoverage)
  }

  def test: ScoverageTests
}
