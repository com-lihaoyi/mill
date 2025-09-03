package mill.kotlinlib.ksp.worker

import com.google.devtools.ksp.impl.KotlinSymbolProcessing
import com.google.devtools.ksp.processing.{
  KspGradleLogger,
  KspJvmArgParserKt,
  SymbolProcessorProvider
}

import java.net.URLClassLoader
import java.util.ServiceLoader
import scala.jdk.CollectionConverters.*

object KspWorker {

  private def toGradleLogLevel(logLevel: LogLevel) = logLevel match {
    case LogLevel.Debug => KspGradleLogger.LOGGING_LEVEL_LOGGING
    case LogLevel.Info => KspGradleLogger.LOGGING_LEVEL_INFO
    case LogLevel.Warn => KspGradleLogger.LOGGING_LEVEL_WARN
    case LogLevel.Error => KspGradleLogger.LOGGING_LEVEL_ERROR
  }

  def runKsp(
      workerArgs: Map[String, String],
      symbolProcessingArgs: Seq[String],
      symbolProcessorClassloader: URLClassLoader
  ): Unit = {

    val logLevelStr = workerArgs.getOrElse("logLevel", LogLevel.Info.toString)
    val logLevel =
      LogLevel.values.find(_.toString == logLevelStr).getOrElse(LogLevel.Warn)

    val gradleLogLevel = toGradleLogLevel(logLevel)

    val config = {
      val configClasspath = KspJvmArgParserKt.kspJvmArgParser(symbolProcessingArgs.toArray)
      configClasspath.getFirst
    }

    val processorProvidersSearch = ServiceLoader.load(
      symbolProcessorClassloader.loadClass("com.google.devtools.ksp.processing.SymbolProcessorProvider"),
      symbolProcessorClassloader
    ).asScala.toList

    val processorProviders: List[SymbolProcessorProvider] =
      processorProvidersSearch.asInstanceOf[List[SymbolProcessorProvider]]

    val logger = new KspGradleLogger(gradleLogLevel)

    val exitCode = new KotlinSymbolProcessing(config, processorProviders.asJava, logger).execute()

    if (exitCode.getCode != 0) {
      throw new Exception(s"KSP failed with exit code ${exitCode.getCode} ($exitCode)")
    }
  }

}
