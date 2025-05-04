package mill.scalalib

import mill.scalalib.api.JvmWorkerUtil

/**
 * Adds version range specific sources when mixed-in to a cross module like
 * `CrossScalaModule` or `CrossSbtModule`.
 * It is useful when a Scala version specific breaking change requires two
 * implementations of the same code for the cross versions before and after
 * a specific version. For example, migrating to Scala 2.13 it is usually
 * needed to define some version specific code for Scala 2.12- (all the versions
 * less or equal 2.12) and for Scala 2.13+ (all the versions greater or equal to 2.13).
 * Mixing `CrossScalaVersionRanges` into a `CrossScalaModule` will automatically add
 * the `src-2.13+` and `src-2.12-`, based on the `crossScalaVersion`.
 */
trait CrossScalaVersionRanges extends CrossModuleBase {
  val crossScalaVersionsRangeAllVersions: Seq[String] = moduleCtx.crossValues.map(_.toString)

  override def scalaVersionDirectoryNames: Seq[String] =
    super.scalaVersionDirectoryNames ++
      JvmWorkerUtil.versionRanges(crossScalaVersion, crossScalaVersionsRangeAllVersions)
}
