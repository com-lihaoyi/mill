package mill.api.internal

/**
 * The environment fingerprint that gates cache reuse: the Mill version, the JVM version, and a
 * hash of the classloader (Mill jars + build dependencies). When any of these differs between when
 * a value was cached and now, the cached value must be discarded regardless of input hashes.
 *
 * This is the single source of truth for both the comparison and the human-readable
 * `name:OLD->NEW` invalidation reason, shared by task-level caching ([[mill.exec]]'s
 * `TaskCacheEntry`) and selective execution (`SelectiveExecutionImpl`) so the two can never emit
 * drifting reason strings. The three fields are stored *flat* in the on-disk `Cached` and
 * `SelectiveExecution.Metadata` schemas; an [[EnvSignature]] is only ever constructed as an
 * in-memory view over them.
 */
private[mill] case class EnvSignature(
    millVersion: String,
    millJvmVersion: String,
    classLoaderSigHash: Int
) {

  /**
   * The reasons, in priority order, that this signature differs from `other` (`this` being the
   * older/cached value and `other` the current one), each formatted as `name:OLD->NEW`. Empty if
   * the signatures are identical. Callers that want a single global reason take `.headOption`.
   */
  def diffReasonsTo(other: EnvSignature): Seq[String] = {
    def reason(name: String, cached: Any, current: Any): Option[String] =
      Option.when(cached != current)(s"$name:$cached->$current")

    Seq(
      reason("mill-version-changed", millVersion, other.millVersion),
      reason("mill-jvm-version-changed", millJvmVersion, other.millJvmVersion),
      reason("classpath-changed", classLoaderSigHash, other.classLoaderSigHash)
    ).flatten
  }
}

private[mill] object EnvSignature {

  /** The environment Mill is currently running in, for the given classloader signature hash. */
  def current(classLoaderSigHash: Int): EnvSignature = EnvSignature(
    millVersion = mill.constants.BuildInfo.millVersion,
    millJvmVersion = sys.props("java.version"),
    classLoaderSigHash = classLoaderSigHash
  )
}
