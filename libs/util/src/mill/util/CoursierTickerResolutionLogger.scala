package mill.util

import coursier.cache.CacheLogger
import mill.api.TaskCtx

import scala.collection.mutable

/**
 * A Coursier Cache.Logger implementation that updates the ticker with the count and
 * overall byte size of artifacts being downloaded.
 *
 * In practice, this ticker output gets prefixed with the current task for which
 * dependencies are being resolved, using a [[mill.util.ProxyLogger]] subclass.
 */

private class CoursierTickerResolutionLogger(ctx: TaskCtx.Log) extends CacheLogger {
  case class DownloadState(var current: Long, var total: Long)

  var downloads = new mutable.TreeMap[String, DownloadState]()
  var totalDownloadCount = 0
  var finishedCount = 0
  var finishedState = DownloadState(0, 0)

  def updateTicker(): Unit = {
    val sums = downloads.values
      .fold(DownloadState(0, 0)) {
        (s1, s2) =>
          DownloadState(
            s1.current + s2.current,
            Math.max(s1.current, s1.total) + Math.max(s2.current, s2.total)
          )
      }
    sums.current += finishedState.current
    sums.total += finishedState.total
    ctx.log.ticker(
      s"Downloading [${downloads.size + finishedCount}/$totalDownloadCount] artifacts (~${sums.current}/${sums.total} bytes)"
    )
  }

  override def downloadingArtifact(url: String): Unit = synchronized {
    totalDownloadCount += 1
    downloads += url -> DownloadState(0, 0)
    updateTicker()
  }

  override def downloadLength(
      url: String,
      totalLength: Long,
      alreadyDownloaded: Long,
      watching: Boolean
  ): Unit = synchronized {
    val state = downloads(url)
    state.current = alreadyDownloaded
    state.total = totalLength
    updateTicker()
  }

  override def downloadProgress(url: String, downloaded: Long): Unit = synchronized {
    val state = downloads(url)
    state.current = downloaded
    updateTicker()
  }

  override def downloadedArtifact(url: String, success: Boolean): Unit = synchronized {
    val state = downloads(url)
    finishedState.current += state.current
    finishedState.total += Math.max(state.current, state.total)
    finishedCount += 1
    downloads -= url
    updateTicker()
  }
}
