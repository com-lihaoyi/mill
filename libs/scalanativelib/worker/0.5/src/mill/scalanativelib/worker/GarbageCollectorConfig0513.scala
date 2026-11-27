package mill.scalanativelib.worker

import java.nio.file.Paths
import scala.concurrent.duration.*
import scala.scalanative.build.{Config, GarbageCollectorConfig as NativeGC, MemorySize}
import mill.scalanativelib.api.GarbageCollectorConfig

// The optional tuning path alone may link Scala Native 0.5.13 symbols.
private object GarbageCollectorConfig0513 {
  def apply(config: Config, options: GarbageCollectorConfig): Config =
    config.withCompilerConfig(config.compilerConfig.withGarbageCollectorConfig { defaults =>
      var result = defaults
      def set[A](value: Option[A])(f: (NativeGC, A) => NativeGC): Unit =
        value.foreach(v => result = f(result, v))

      set(options.initialHeapSizeBytes)((c, n) => c.withInitialHeapSize(MemorySize.bytes(n)))
      set(options.maxHeapSizeBytes)((c, n) => c.withMaxHeapSize(MemorySize.bytes(n)))
      set(options.threadHeapBlockSizeBytes)((c, n) =>
        c.withThreadHeapBlockSize(MemorySize.bytes(n))
      )
      set(options.threads)(_.withThreads(_))
      set(options.timeRatio)(_.withTimeRatio(_))
      set(options.freeRatio)(_.withFreeRatio(_))
      set(options.maxFreeRatio)(_.withMaxFreeRatio(_))
      set(options.syncTimeoutMillis)((c, n) => c.withSyncTimeout(n.millis))
      set(options.syncWarningIntervalMillis)((c, n) => c.withSyncWarningInterval(n.millis))
      set(options.logLevel)((c, s) => c.withLogLevel(NativeGC.LogLevel(s)))
      set(options.logFile)((c, s) => c.withLogFile(Paths.get(s)))
      set(options.statsFile)((c, s) => c.withStatsFile(Paths.get(s)))
      set(options.statsEnabled)(_.withStatsEnabled(_))
      set(options.trapBasedYieldPoints)(_.withTrapBasedYieldPoints(_))
      result
    })
}
