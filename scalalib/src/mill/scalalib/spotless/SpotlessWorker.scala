package mill.scalalib.spotless

import mill._
import mill.define.{Discover, ExternalModule, Worker}
import mill.api.{Ctx, PathRef, Result}
import com.diffplug.spotless.{Formatter, FormatExceptionPolicyStrict}
import com.diffplug.spotless.generic.LicenseHeaderStep
import com.diffplug.spotless.java.GoogleJavaFormatStep
import com.diffplug.spotless.scala.ScalafmtStep
import com.diffplug.spotless.kotlin.KtlintStep
import com.typesafe.config.ConfigFactory

import scala.collection.mutable
import scala.util.control.NonFatal

object SpotlessWorkerModule extends ExternalModule {
  def worker: Worker[SpotlessWorker] = T.worker { new SpotlessWorker() }

  lazy val millDiscover: Discover = Discover[this.type]
}

private[spotless] class SpotlessWorker extends AutoCloseable {
  private val reformatted: mutable.Map[os.Path, Int] = mutable.Map.empty
  private var configSig: Int = 0

  def reformat(input: Seq[PathRef], spotlessConfig: PathRef)(implicit ctx: Ctx): Unit = {
    reformatAction(input, spotlessConfig, dryRun = false)
  }

  def checkFormat(input: Seq[PathRef], spotlessConfig: PathRef)(implicit ctx: Ctx): Result[Unit] = {
    val misformatted = reformatAction(input, spotlessConfig, dryRun = true)
    if (misformatted.isEmpty) {
      Result.Success(())
    } else {
      val out = ctx.log.outputStream
      for (u <- misformatted) {
        out.println(u.path.toString)
      }
      Result.Failure(s"Found ${misformatted.length} misformatted files")
    }
  }

  private def reformatAction(
      input: Seq[PathRef],
      spotlessConfig: PathRef,
      dryRun: Boolean
  )(implicit ctx: Ctx): Seq[PathRef] = {
    
    // Only consider files that have changed since last reformat
    val toConsider =
      if (spotlessConfig.sig != configSig) input
      else input.filterNot(ref => reformatted.get(ref.path).contains(ref.sig))

    if (toConsider.nonEmpty) {
      if (dryRun) {
        ctx.log.info(s"Checking format of ${toConsider.size} sources")
      } else {
        ctx.log.info(s"Formatting ${toConsider.size} sources")
      }

      try {
        val formatter = createFormatter(spotlessConfig)
        val misformatted = mutable.ListBuffer.empty[PathRef]

        def markFormatted(path: PathRef) = {
          val updRef = PathRef(path.path)
          reformatted += updRef.path -> updRef.sig
        }

        toConsider.foreach { pathToFormat =>
          val file = pathToFormat.path.toIO
          if (!formatter.isCleanFile(file)) {
            misformatted += pathToFormat
            if (!dryRun) {
              formatter.applyToFile(file)
              markFormatted(pathToFormat)
            }
          } else {
            markFormatted(pathToFormat)
          }
        }

        configSig = spotlessConfig.sig
        misformatted.toList
      } catch {
        case NonFatal(e) =>
          ctx.log.error(s"Failed to format files: ${e.getMessage}")
          throw e
      }
    } else {
      ctx.log.info("Everything is formatted already")
      Nil
    }
  }

  private def createFormatter(config: PathRef): Formatter = {
    val builder = Formatter.builder()
      .withEncoding("UTF-8")
      .withPolicy(new FormatExceptionPolicyStrict())
    
    if (os.exists(config.path)) {
      val conf = ConfigFactory.parseFile(config.path.toIO)
      
      // Java configuration
      if (conf.hasPath("java")) {
        builder.addStep(GoogleJavaFormatStep.create())
        // Add other Java steps based on configuration
      }
      
      // Scala configuration
      if (conf.hasPath("scala")) {
        val scalaConf = conf.getConfig("scala")
        val scalafmtVersion = scalaConf.getString("scalafmt.version")
        val scalafmtConfigFile = scalaConf.getString("scalafmt.configFile")
        builder.addStep(ScalafmtStep.create(scalafmtVersion, scalafmtConfigFile))
      }
      
      // Kotlin configuration
      if (conf.hasPath("kotlin")) {
        val kotlinConf = conf.getConfig("kotlin")
        val ktlintVersion = kotlinConf.getString("ktlint.version")
        builder.addStep(KtlintStep.create(ktlintVersion))
      }
      
      // Common formatting
      if (conf.hasPath("misc")) {
        builder.trimTrailingWhitespace()
        builder.endWithNewline()
      }
    }

    builder.build()
  }

  override def close(): Unit = {
    reformatted.clear()
    configSig = 0
  }
}