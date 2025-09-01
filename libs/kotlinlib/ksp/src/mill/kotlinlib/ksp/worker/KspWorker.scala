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

class KspWorker {

  def loggingLevel(logLevel: LogLevel) = logLevel match {
    case LogLevel.Debug => KspGradleLogger.LOGGING_LEVEL_LOGGING
    case LogLevel.Info => KspGradleLogger.LOGGING_LEVEL_INFO
    case LogLevel.Warn => KspGradleLogger.LOGGING_LEVEL_WARN
    case LogLevel.Error => KspGradleLogger.LOGGING_LEVEL_ERROR
  }

  def runKsp(workerArgs: KspWorkerArgs, symbolProcessingArgs: Seq[String]): Unit = {

    val logLevel = loggingLevel(workerArgs.logLevel)

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
    val logger = new KspGradleLogger(logLevel)

    val exitCode = new KotlinSymbolProcessing(config, processorProviders.asJava, logger).execute()

    if (exitCode.getCode != 0) {
      throw new Exception(s"KSP failed with exit code ${exitCode.getCode} ($exitCode)")
    }
  }

}
