package mill.lib.scoverage

import mill._
import mill.scalalib._

import _root_.scoverage.Serializer.{ coverageFile, deserialize }
import _root_.scoverage.IOUtils.{ findMeasurementFiles, invoked }
import _root_.scoverage.report.ScoverageHtmlWriter

trait ScoverageModule { outer: ScalaModule =>

  trait ScoverageCompile extends ScalaModule {
    def dataDir: String
    def sources = outer.sources
    def resources = outer.resources
    def scalaVersion = outer.scalaVersion()
    def compileIvyDeps = outer.compileIvyDeps()
    def ivyDeps = outer.ivyDeps() ++
      Agg(ivy"org.scoverage::scalac-scoverage-runtime:1.3.1")
    def scalacPluginIvyDeps = outer.scalacPluginIvyDeps() ++
      Agg(ivy"org.scoverage::scalac-scoverage-plugin:1.3.1")
    def scalacOptions = outer.scalacOptions() ++
      Seq(s"-P:scoverage:dataDir:$dataDir")

    def html() = T.command {
      val coverageFileObj = coverageFile(dataDir)
      if (coverageFileObj.exists) {
        val coverage = deserialize(coverageFileObj)
        coverage(invoked(findMeasurementFiles(dataDir)))
        val Seq(PathRef(sourceFolderPath, _, _)) = sources()
        val sourceFolders = Seq(sourceFolderPath.toIO)
        val htmlFolder = new java.io.File(s"${dataDir}/html")
        htmlFolder.mkdir()
        new ScoverageHtmlWriter(sourceFolders, htmlFolder, None)
          .write(coverage)
      } else {
        T.ctx().log.error(s"Cannot write scoverage report. Directory ${dataDir} does not exist!")
      }
    }
  }

  trait ScoverageTests { inner: TestModule with ScalaModule =>
    override def moduleDeps: Seq[JavaModule] = Seq(outer.scoverage)
  }

  def scoverage: ScoverageCompile
  def test: ScoverageTests
}
