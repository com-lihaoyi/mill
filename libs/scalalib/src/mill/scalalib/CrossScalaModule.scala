package mill.scalalib

/**
 * A [[ScalaModule]] which is suited to be used with [[mill.api.Cross]].
 * It supports additional source directories with the scala version pattern
 * as suffix (`src-{scalaversionprefix}`), e.g.
 *
 * - src
 * - src-2.11
 * - src-2.12.3
 */
trait CrossScalaModule extends ScalaModule with CrossModuleBase {
  override def sourcesFolders: Seq[os.SubPath] = super.sourcesFolders.flatMap {
    source => Seq(source) ++ scalaVersionDirectoryNames.map(s => os.sub / s"src-$s")
  }
}
