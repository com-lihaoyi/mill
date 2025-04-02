/**
 * Vendored copy of https://github.com/lefou/mill-vcs-version
 * to avoid circular dependency when bootstrapping Mill
 */
package mill.util

case class Vcs(val name: String)

object Vcs {
  def git = Vcs("git")

  implicit val jsonify: upickle.default.ReadWriter[Vcs] = upickle.default.macroRW
}
