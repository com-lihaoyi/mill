package mill.contrib.scoverage

import coursier.Repository
import mill._
import mill.api.{Loose, PathRef}
import mill.main.BuildInfo
import mill.contrib.scoverage.api.ScoverageReportWorkerApi.ReportType
import mill.scalalib.api.ZincWorkerUtil
import mill.scalalib.{Dep, DepSyntax, JavaModule, ScalaModule}
import mill.api.Result
import mill.util.Util.millProjectModule

import scala.util.Try

/**
 * Adds targets to a [[mill.scalalib.ScalaModule]] to create test coverage reports.
 *
 * This module allows you to generate code coverage reports for Scala projects with
 * [[https://github.com/scoverage Scoverage]] via the
 * [[https://github.com/scoverage/scalac-scoverage-plugin scoverage compiler plugin]].
 *
 * To declare a module for which you want to generate coverage reports you can
 * Extends the `mill.contrib.scoverage.ScoverageModule` trait when defining your
 * Module. Additionally, you must define a submodule that extends the
 * `ScoverageTests` trait that belongs to your instance of `ScoverageModule`.
 *
 * {{{
 * // You have to replace VERSION
 * import $ivy.`com.lihaoyi::mill-contrib-buildinfo:VERSION`
 * import mill.contrib.scoverage.ScoverageModule
 *
 * Object foo extends ScoverageModule  {
 *   def scalaVersion = "2.12.9"
 *   def scoverageVersion = "1.4.0"
 *
 *   object test extends ScoverageTests {
 *     def ivyDeps = Agg(ivy"org.scalatest::scalatest:3.0.5")
 *     def testFrameworks = Seq("org.scalatest.tools.Framework")
 *   }
 * }
 * }}}
 *
 * In addition to the normal tasks available to your Scala module, Scoverage
 * Modules introduce a few new tasks and changes the behavior of an existing one.
 *
 * - mill foo.scoverage.compile      # compiles your module with test instrumentation
 *                                 # (you don't have to run this manually, running the test task will force its invocation)
 *
 * - mill foo.test                   # tests your project and collects metrics on code coverage
 * - mill foo.scoverage.htmlReport   # uses the metrics collected by a previous test run to generate a coverage report in html format
 * - mill foo.scoverage.xmlReport    # uses the metrics collected by a previous test run to generate a coverage report in xml format
 *
 * The measurement data by default is available at `out/foo/scoverage/dataDir.dest/`,
 * the html report is saved in `out/foo/scoverage/htmlReport.dest/`,
 * and the xml report is saved in `out/foo/scoverage/xmlReport.dest/`.
 */
trait ScoverageModule extends ScalaModule { outer: ScalaModule =>

  /**
   * The Scoverage version to use.
   */
  def scoverageVersion: T[String]

  private def isScoverage2: Task[Boolean] = T.task { scoverageVersion().startsWith("2.") }

  private def isScala3: Task[Boolean] = T.task { ZincWorkerUtil.isScala3(outer.scalaVersion()) }

  def scoverageRuntimeDeps: T[Agg[Dep]] = T {
    if (isScala3()) {
      Agg.empty
    } else {
      Agg(ivy"org.scoverage::scalac-scoverage-runtime:${outer.scoverageVersion()}")
    }
  }

  def scoveragePluginDeps: T[Agg[Dep]] = T {
    val sv = scoverageVersion()
    if (isScala3()) {
      Agg.empty
    } else {
      if (isScoverage2()) {
        Agg(
          ivy"org.scoverage:::scalac-scoverage-plugin:${sv}",
          ivy"org.scoverage::scalac-scoverage-domain:${sv}",
          ivy"org.scoverage::scalac-scoverage-serializer:${sv}",
          ivy"org.scoverage::scalac-scoverage-reporter:${sv}"
        )
      } else {
        Agg(ivy"org.scoverage:::scalac-scoverage-plugin:${sv}")
      }
    }
  }

  private def checkVersions = T.task {
    val sv = scalaVersion()
    val isSov2 = scoverageVersion().startsWith("2.")
    (sv.split('.'), isSov2) match {
      case (Array("3", "0" | "1", _*), _) => Result.Failure(
          "Scala 3.0 and 3.1 is not supported by Scoverage. You have to update to at least Scala 3.2 and Scoverage 2.0"
        )
      case (Array("3", _*), false) => Result.Failure(
          "Scoverage 1.x does not support Scala 3. You have to update to at least Scala 3.2 and Scoverage 2.0"
        )
      case (Array("2", "11", _*), true) => Result.Failure(
          "Scoverage 2.x is not compatible with Scala 2.11. Consider using Scoverage 1.x or switch to a newer Scala version."
        )
      case _ =>
    }
  }

  def scoverageToolsClasspath: T[Agg[PathRef]] = T {
    checkVersions()

    scoverageReportWorkerClasspath() ++
      resolveDeps(T.task {
        // we need to resolve with same Scala version used for Mill, not the project Scala version
        val scalaBinVersion = ZincWorkerUtil.scalaBinaryVersion(BuildInfo.scalaVersion)
        val sv = scoverageVersion()

        val baseDeps = Agg(
          ivy"org.scoverage:scalac-scoverage-domain_${scalaBinVersion}:${sv}",
          ivy"org.scoverage:scalac-scoverage-serializer_${scalaBinVersion}:${sv}",
          ivy"org.scoverage:scalac-scoverage-reporter_${scalaBinVersion}:${sv}"
        )

        val scalaVersion = BuildInfo.scalaVersion.split("[.]").toList match {
          // Scoverage 1 is not released for Scala > 2.13.8, but we don't need to compiler specific code,
          // only the reporter API, which does not depend on the Compiler API, so using another full Scala version
          // should be safe
          case "2" :: "13" :: c :: _ if sv.startsWith("1.") && Try(c.toInt).getOrElse(0) > 8 =>
            val v = "2.13.8"
            T.log.outputStream.println(
              s"Detected an unsupported Scala version (${BuildInfo.scalaVersion}). Using Scala version ${v} to resolve scoverage ${sv} reporting API."
            )
            v
          case _ => BuildInfo.scalaVersion
        }

        val pluginDep =
          Agg(ivy"org.scoverage:scalac-scoverage-plugin_${scalaVersion}:${sv}")

        val deps = if (isScala3() && isScoverage2()) {
          baseDeps
        } else if (isScoverage2()) {
          baseDeps ++ pluginDep
        } else {
          pluginDep
        }
        deps.map(bindDependency())
      })()
  }

  def scoverageClasspath: T[Agg[PathRef]] = T {
    resolveDeps(T.task {
      scoveragePluginDeps().map(bindDependency())
    })()
  }

  def scoverageReportWorkerClasspath: T[Agg[PathRef]] = T {
    val isScov2 = isScoverage2()

    val workerArtifact =
      if (isScov2) "mill-contrib-scoverage-worker2"
      else "mill-contrib-scoverage-worker"

    millProjectModule(
      workerArtifact,
      repositoriesTask(),
      resolveFilter = _.toString.contains(workerArtifact)
    )
  }

  /** Inner worker module. This is not an `object` to allow users to override and customize it. */
  lazy val scoverage: ScoverageData = new ScoverageData {}

  trait ScoverageData extends ScalaModule {

    def doReport(reportType: ReportType): Task[Unit] = T.task {
      ScoverageReportWorker
        .scoverageReportWorker()
        .bridge(scoverageToolsClasspath())
        .report(reportType, allSources().map(_.path), Seq(data().path), T.workspace)
    }

    /**
     * The persistent data dir used to store scoverage coverage data.
     * Use to store coverage data at compile-time and by the various report targets.
     */
    def data: T[PathRef] = T.persistent {
      // via the persistent target, we ensure, the dest dir doesn't get cleared
      PathRef(T.dest)
    }

    override def generatedSources: Target[Seq[PathRef]] = T { outer.generatedSources() }
    override def allSources: Target[Seq[PathRef]] = T { outer.allSources() }
    override def moduleDeps: Seq[JavaModule] = outer.moduleDeps
    override def compileModuleDeps: Seq[JavaModule] = outer.compileModuleDeps
    override def sources: T[Seq[PathRef]] = T.sources { outer.sources() }
    override def resources: T[Seq[PathRef]] = T.sources { outer.resources() }
    override def scalaVersion = T { outer.scalaVersion() }
    override def repositoriesTask: Task[Seq[Repository]] = T.task { outer.repositoriesTask() }
    override def compileIvyDeps: Target[Agg[Dep]] = T { outer.compileIvyDeps() }
    override def ivyDeps: Target[Agg[Dep]] =
      T { outer.ivyDeps() ++ outer.scoverageRuntimeDeps() }
    override def unmanagedClasspath: Target[Agg[PathRef]] = T { outer.unmanagedClasspath() }

    /** Add the scoverage scalac plugin. */
    override def scalacPluginIvyDeps: Target[Loose.Agg[Dep]] =
      T { outer.scalacPluginIvyDeps() ++ outer.scoveragePluginDeps() }

    /** Add the scoverage specific plugin settings (`dataDir`). */
    override def scalacOptions: Target[Seq[String]] =
      T {
        val extras =
          if (isScala3()) {
            Seq(s"-coverage-out:${data().path.toIO.getPath()}")
          } else {
            val base = s"-P:scoverage:dataDir:${data().path.toIO.getPath()}"
            if (isScoverage2()) Seq(base, s"-P:scoverage:sourceRoot:${T.workspace}")
            else Seq(base)
          }

        outer.scalacOptions() ++ extras
      }

    def htmlReport(): Command[Unit] = T.command { doReport(ReportType.Html) }
    def xmlReport(): Command[Unit] = T.command { doReport(ReportType.Xml) }
    def consoleReport(): Command[Unit] = T.command { doReport(ReportType.Console) }

    override def skipIdea = outer.skipIdea
  }

  trait ScoverageTests extends ScalaTests {
    override def upstreamAssemblyClasspath = T {
      super.upstreamAssemblyClasspath() ++
        resolveDeps(T.task {
          outer.scoverageRuntimeDeps().map(bindDependency())
        })()
    }
    override def compileClasspath = T {
      super.compileClasspath() ++
        resolveDeps(T.task {
          outer.scoverageRuntimeDeps().map(bindDependency())
        })()
    }
    override def runClasspath = T {
      super.runClasspath() ++
        resolveDeps(T.task {
          outer.scoverageRuntimeDeps().map(bindDependency())
        })()
    }

    // Need the sources compiled with scoverage instrumentation to run.
    override def moduleDeps: Seq[JavaModule] = Seq(outer.scoverage)
  }
}
