package mill.scalacoverage

import java.net.URLClassLoader

import ammonite.ops.Path
import mill.define.{Discover, Worker}
import mill.util.Logger
import mill.{Agg, T}


class ScoverageWorker {
  private var scalaInstanceCache = Option.empty[(Long, ScoverageWorkerApi)]

  def impl(toolsClasspath: Agg[Path]): ScoverageWorkerApi = {
    // TODO: this method could be extracted from mill.scalanativelib.ScalaNativeWorker.impl() for re-use, it's duplicated here
    val classloaderSig = toolsClasspath.map(p => p.toString().hashCode + p.mtime.toMillis).sum
    scalaInstanceCache match {
      case Some((sig, bridge)) if sig == classloaderSig => bridge
      case _ =>
        val cl = new URLClassLoader(
          toolsClasspath.map(_.toIO.toURI.toURL).toArray,
          getClass.getClassLoader
        )
        try {
          val bridge = cl
            .loadClass("mill.scalacoverage.ScoverageWorkerImpl")
            .getDeclaredConstructor()
            .newInstance()
            .asInstanceOf[ScoverageWorkerApi]
          scalaInstanceCache = Some((classloaderSig, bridge))
          bridge
        }
        catch {
          case e: Exception =>
            e.printStackTrace()
            throw e
        }
    }
  }
}

trait ScoverageWorkerApi {

  /** Produces a coverage report.
    *
    * @param options the scoverage options
    * @param dataDir the directory with coverage data
    * @param reportDir the directory to which reports should be written
    * @param compileSourceDirectories the source directories
    * @param encoding optionally the encoding
    * @param log a logger to write any build messages.
    */
  def coverageReport(options: ScoverageOptions,
                     dataDir: Path,
                     reportDir: Path,
                     compileSourceDirectories: Seq[Path],
                     encoding: Option[String],
                     log: Logger): Unit

  /** Aggregates multiple coverage reports.
    *
    * @param options the scoverage options
    * @param coverageReportDirs the directories with coverage data
    * @param aggregateReportDir the directory to which the aggregate report should be written
    * @param compileSourceDirectories the source directories
    * @param encoding optionally the encoding
    * @param log a logger to write any build messages.
    */
  def aggregateCoverage(options: ScoverageOptions,
                        coverageReportDirs: Seq[Path],
                        aggregateReportDir: Path,
                        compileSourceDirectories: Seq[Path],
                        encoding: Option[String],
                        log: Logger): Unit
}

object ScoverageWorkerApi extends mill.define.ExternalModule {
  def scoverageWorker: Worker[ScoverageWorker] = T.worker { new ScoverageWorker() }

  lazy val millDiscover = Discover[this.type]
}
