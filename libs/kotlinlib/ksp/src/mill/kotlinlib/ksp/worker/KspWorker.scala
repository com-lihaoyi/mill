package mill.kotlinlib.ksp.worker

import com.google.devtools.ksp.impl.KotlinSymbolProcessing
import com.google.devtools.ksp.processing.{
  KspGradleLogger,
  KspJvmArgParserKt,
  SymbolProcessorProvider
}

import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader
import scala.jdk.CollectionConverters.*

object KspWorker {

  def toGradleLogLevel(logLevel: LogLevel) = logLevel match {
    case LogLevel.Debug => KspGradleLogger.LOGGING_LEVEL_LOGGING
    case LogLevel.Info => KspGradleLogger.LOGGING_LEVEL_INFO
    case LogLevel.Warn => KspGradleLogger.LOGGING_LEVEL_WARN
    case LogLevel.Error => KspGradleLogger.LOGGING_LEVEL_ERROR
  }

  def runKsp(workerArgs: Map[String, String], symbolProcessingArgs: Seq[String]): Unit = {

    val logLevelStr = workerArgs.getOrElse("logLevel", LogLevel.Info.toString)
    val logLevel =
      LogLevel.values.find(_.toString == logLevelStr).getOrElse(LogLevel.Warn)

    val gradleLogLevel = toGradleLogLevel(logLevel)

    val (config, classpath) = {
      val configClasspath = KspJvmArgParserKt.kspJvmArgParser(symbolProcessingArgs.toArray)
      configClasspath.getFirst -> configClasspath.getSecond.asScala
    }

    val processorClassloader = new URLClassLoader(classpath.map { pathStr =>
      new File(pathStr).toURI.toURL
    }.toArray)

    val processorProviders: List[SymbolProcessorProvider] = ServiceLoader.load(
      processorClassloader.loadClass("com.google.devtools.ksp.processing.SymbolProcessorProvider"),
      processorClassloader
    ).asScala.toList.collect {
      case provider: SymbolProcessorProvider => provider
    }
    val logger = new KspGradleLogger(gradleLogLevel)

    val exitCode = new KotlinSymbolProcessing(config, processorProviders.asJava, logger).execute()

    if (exitCode.getCode != 0) {
      throw new Exception(s"KSP failed with exit code ${exitCode.getCode} ($exitCode)")
    }
  }

}
