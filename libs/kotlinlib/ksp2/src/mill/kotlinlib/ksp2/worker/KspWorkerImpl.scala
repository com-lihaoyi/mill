package mill.kotlinlib.ksp2.worker

import mill.kotlinlib.ksp2.KspWorker

import com.google.devtools.ksp.impl.KotlinSymbolProcessing
import com.google.devtools.ksp.processing.{
  KspGradleLogger,
  KspJvmArgParserKt,
  SymbolProcessorProvider
}
import mill.kotlinlib.ksp2.{KspWorkerArgs, LogLevel}

import java.util.ServiceLoader
import scala.jdk.CollectionConverters.*

/**
 * This class implements the in-process KSP against the KSP API which is only present at compile time.
 * The implementation is derived from [[https://github.com/google/ksp/blob/main/docs/ksp2entrypoints.md]] with
 * the major difference being that instead of creating a new classloader, users are expected to pass the classloader
 * which contains the symbol processors (for example dagger-compiler, hilt-android-compiler, micronaut-inject-kotlin etc).
 *
 * The provided classloader needs to be a child of the classloader of this, otherwise the discovery result of user defined processors
 * will fail to be cast to [[com.google.devtools.ksp.processing.SymbolProcessorProvider]].
 */
class KspWorkerImpl extends KspWorker {

  private def toGradleLogLevel(logLevel: LogLevel) = logLevel match {
    case LogLevel.Debug => KspGradleLogger.LOGGING_LEVEL_LOGGING
    case LogLevel.Info => KspGradleLogger.LOGGING_LEVEL_INFO
    case LogLevel.Warn => KspGradleLogger.LOGGING_LEVEL_WARN
    case LogLevel.Error => KspGradleLogger.LOGGING_LEVEL_ERROR
  }

  def runKsp(
      symbolProcessorClassloader: ClassLoader,
      kspWorkerArgs: KspWorkerArgs,
      symbolProcessingArgs: Seq[String]
  ): Unit = {

    val gradleLogLevel = toGradleLogLevel(kspWorkerArgs.logLevel)

    val config = {
      val configClasspath = KspJvmArgParserKt.kspJvmArgParser(symbolProcessingArgs.toArray)
      configClasspath.getFirst
    }

    val processorProvidersSearch = ServiceLoader.load(
      symbolProcessorClassloader.loadClass(
        "com.google.devtools.ksp.processing.SymbolProcessorProvider"
      ),
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
