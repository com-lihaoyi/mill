package mill.scalanativelib.api

import upickle.{ReadWriter, readwriter}

/**
 * Scala Native 0.5.13+ tuning for the garbage collector selected with `nativeGC`.
 * Unset parameters retain Scala Native's existing defaults and environment overrides.
 */
sealed trait GarbageCollectorConfig {
  def initialHeapSizeBytes: Option[Long]
  def maxHeapSizeBytes: Option[Long]
  def threadHeapBlockSizeBytes: Option[Long]
  def threads: Option[Int]
  def timeRatio: Option[Double]
  def freeRatio: Option[Double]
  def maxFreeRatio: Option[Double]
  def syncTimeoutMillis: Option[Long]
  def syncWarningIntervalMillis: Option[Long]
  def logLevel: Option[String]
  def logFile: Option[String]
  def statsFile: Option[String]
  def statsEnabled: Option[Boolean]
  def trapBasedYieldPoints: Option[Boolean]

  def withInitialHeapSizeBytes(value: Long): GarbageCollectorConfig
  def withMaxHeapSizeBytes(value: Long): GarbageCollectorConfig
  def withThreadHeapBlockSizeBytes(value: Long): GarbageCollectorConfig
  def withThreads(value: Int): GarbageCollectorConfig
  def withTimeRatio(value: Double): GarbageCollectorConfig
  def withFreeRatio(value: Double): GarbageCollectorConfig
  def withMaxFreeRatio(value: Double): GarbageCollectorConfig
  def withSyncTimeoutMillis(value: Long): GarbageCollectorConfig
  def withSyncWarningIntervalMillis(value: Long): GarbageCollectorConfig
  def withLogLevel(value: String): GarbageCollectorConfig
  def withLogFile(value: String): GarbageCollectorConfig
  def withStatsFile(value: String): GarbageCollectorConfig
  def withStatsEnabled(value: Boolean): GarbageCollectorConfig
  def withTrapBasedYieldPoints(value: Boolean): GarbageCollectorConfig
}

object GarbageCollectorConfig {
  val empty: GarbageCollectorConfig = GarbageCollectorConfigImpl.empty
  given ReadWriter[GarbageCollectorConfig] = GarbageCollectorConfigImpl.readWriter
}

private object GarbageCollectorConfigImpl {
  private final case class Data(
      initialHeapSizeBytes: Option[Long] = None,
      maxHeapSizeBytes: Option[Long] = None,
      threadHeapBlockSizeBytes: Option[Long] = None,
      threads: Option[Int] = None,
      timeRatio: Option[Double] = None,
      freeRatio: Option[Double] = None,
      maxFreeRatio: Option[Double] = None,
      syncTimeoutMillis: Option[Long] = None,
      syncWarningIntervalMillis: Option[Long] = None,
      logLevel: Option[String] = None,
      logFile: Option[String] = None,
      statsFile: Option[String] = None,
      statsEnabled: Option[Boolean] = None,
      trapBasedYieldPoints: Option[Boolean] = None
  ) derives ReadWriter

  private final class Impl(val data: Data) extends GarbageCollectorConfig {
    def initialHeapSizeBytes: Option[Long] = data.initialHeapSizeBytes
    def maxHeapSizeBytes: Option[Long] = data.maxHeapSizeBytes
    def threadHeapBlockSizeBytes: Option[Long] = data.threadHeapBlockSizeBytes
    def threads: Option[Int] = data.threads
    def timeRatio: Option[Double] = data.timeRatio
    def freeRatio: Option[Double] = data.freeRatio
    def maxFreeRatio: Option[Double] = data.maxFreeRatio
    def syncTimeoutMillis: Option[Long] = data.syncTimeoutMillis
    def syncWarningIntervalMillis: Option[Long] = data.syncWarningIntervalMillis
    def logLevel: Option[String] = data.logLevel
    def logFile: Option[String] = data.logFile
    def statsFile: Option[String] = data.statsFile
    def statsEnabled: Option[Boolean] = data.statsEnabled
    def trapBasedYieldPoints: Option[Boolean] = data.trapBasedYieldPoints

    def withInitialHeapSizeBytes(value: Long): GarbageCollectorConfig =
      new Impl(data.copy(initialHeapSizeBytes = Some(value)))

    def withMaxHeapSizeBytes(value: Long): GarbageCollectorConfig =
      new Impl(data.copy(maxHeapSizeBytes = Some(value)))

    def withThreadHeapBlockSizeBytes(value: Long): GarbageCollectorConfig =
      new Impl(data.copy(threadHeapBlockSizeBytes = Some(value)))

    def withThreads(value: Int): GarbageCollectorConfig =
      new Impl(data.copy(threads = Some(value)))

    def withTimeRatio(value: Double): GarbageCollectorConfig =
      new Impl(data.copy(timeRatio = Some(value)))

    def withFreeRatio(value: Double): GarbageCollectorConfig =
      new Impl(data.copy(freeRatio = Some(value)))

    def withMaxFreeRatio(value: Double): GarbageCollectorConfig =
      new Impl(data.copy(maxFreeRatio = Some(value)))

    def withSyncTimeoutMillis(value: Long): GarbageCollectorConfig =
      new Impl(data.copy(syncTimeoutMillis = Some(value)))

    def withSyncWarningIntervalMillis(value: Long): GarbageCollectorConfig =
      new Impl(data.copy(syncWarningIntervalMillis = Some(value)))

    def withLogLevel(value: String): GarbageCollectorConfig =
      new Impl(data.copy(logLevel = Some(value)))

    def withLogFile(value: String): GarbageCollectorConfig =
      new Impl(data.copy(logFile = Some(value)))

    def withStatsFile(value: String): GarbageCollectorConfig =
      new Impl(data.copy(statsFile = Some(value)))

    def withStatsEnabled(value: Boolean): GarbageCollectorConfig =
      new Impl(data.copy(statsEnabled = Some(value)))

    def withTrapBasedYieldPoints(value: Boolean): GarbageCollectorConfig =
      new Impl(data.copy(trapBasedYieldPoints = Some(value)))

    override def equals(other: Any): Boolean = other match {
      case that: Impl => data == that.data
      case _ => false
    }

    override def hashCode(): Int = data.hashCode()
  }

  val empty: GarbageCollectorConfig = new Impl(Data())
  val readWriter: ReadWriter[GarbageCollectorConfig] =
    readwriter[Data].bimap({ case value: Impl => value.data }, new Impl(_))
}
