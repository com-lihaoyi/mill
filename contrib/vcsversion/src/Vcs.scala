package mill.vcs.version

case class Vcs(val name: String)

object Vcs {
  def git = Vcs("git")

  implicit val jsonify: upickle.default.ReadWriter[Vcs] = upickle.default.macroRW
}
