package mill.scalalib

import mill.api.PathRef
import mill.{T, Task}

/**
 * A [[ScalaModule]] which is suited to be used with [[mill.define.Cross]].
 * It supports additional source directories with the scala version pattern
 * as suffix (`src-{scalaversionprefix}`), e.g.
 *
 * - src
 * - src-2.11
 * - src-2.12.3
 */
trait CrossScalaModule extends ScalaModule with CrossModuleBase {
  def versionSourcesPaths = scalaVersionDirectoryNames.map(s => os.sub / s"src-$s")
  def versionSources = Task.Sources(versionSourcesPaths*)
  override def sources: T[Seq[PathRef]] = Task {super.sources() ++ versionSources()}
}
