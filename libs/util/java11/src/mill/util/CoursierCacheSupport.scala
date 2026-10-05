package mill.util

import java.util.concurrent.ConcurrentHashMap

/**
 * Helpers around the coursier cache instance Mill gets from `coursier.cache.Cache.default`.
 *
 * Mill applies a number of settings to that cache (cache location, credentials, TTL, offline
 * mode, …), but those are only available on `coursier.cache.FileCache`. Users can substitute
 * another `Cache` implementation via coursier's own configuration, in which case Mill has to
 * drop those settings - hence the warning below.
 */
private[mill] object CoursierCacheSupport {

  private val warned = ConcurrentHashMap.newKeySet[String]()

  /**
   * Warns that `cache` isn't a `FileCache`, so `settings` could not be applied to it.
   *
   * Only warns once per distinct message, as the coursier cache is looked up again for
   * every resolution.
   */
  def warnNotFileCache(cache: AnyRef, settings: Seq[String], log: String => Unit): Unit = {
    val message =
      s"The coursier cache is a ${cache.getClass.getName} rather than a FileCache, so Mill " +
        s"cannot apply the following settings to it: ${settings.mkString(", ")}. " +
        "Dependency resolution may fail or behave unexpectedly."
    if (warned.add(message)) log(message)
  }
}
