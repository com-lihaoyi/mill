package mill.main

import mill.*
import mill.api.SelectMode
import mill.define.{Discover, Evaluator, ExternalModule}

import mainargs.arg

/**
 * Handles Bash and Zsh tab completions, which provide an array of tokens in the current
 * shell and the index of the token currently being completed.
 */
object TabCompletionModule extends ExternalModule {

  lazy val millDiscover = Discover[this.type]

  def tabComplete(
      ev: Evaluator,
      @arg(positional = true) index: Int,
      args: mainargs.Leftover[String]
  ) = Task.Command(exclusive = true) {
    val currentToken = args.value(index)
    val deSlashed = currentToken.replace("\\", "")
    val trimmed = deSlashed.take(
      deSlashed.lastIndexWhere(c => !c.isLetterOrDigit && !"-_,".contains(c)) + 1
    )
    val query = trimmed.lastOption match {
      case None => "_"
      case Some('.') => trimmed + "_"
      case Some('[') => trimmed + "__]"
      case Some(',') => trimmed + "__]"
      case Some(']') => trimmed + "._"
    }

    ev.resolveSegments(Seq(query), SelectMode.Multi).map { res =>
      res.map(_.render).filter(_.startsWith(deSlashed)).foreach(println)
    }
  }

  def install() = Task.Command(exclusive = true) {
    val script =
      """_mill_bash() {
        |  # compopt makes bash not insert a newline after each completion, which
        |  # is what we want for modules. Only works for bash 4+
        |  compopt -o nospace 2>/dev/null
        |  COMPREPLY=( $(${COMP_WORDS[0]} --tab-complete "$COMP_CWORD" "${COMP_WORDS[@]}") )
        |}
        |
        |_mill_zsh() {
        |  # `-S` to avoid the trailing space after a completion, since it is
        |  # common that the user will want to put a `.` and continue typing
        |  #
        |  # zsh $CURRENT is 1-indexed while bash $COMP_CWORD is 0-indexed, so
        |  # subtract 1 from zsh's variable so Mill gets a consistent index
        |  compadd -S '' -- $($words[1] --tab-complete "$((CURRENT - 1))" $words)
        |}
        |
        |if [ -n "${ZSH_VERSION:-}" ]; then
        |  autoload -Uz compinit
        |  compinit
        |  compdef _mill_zsh mill
        |elif [ -n "${BASH_VERSION:-}" ]; then
        |  complete -F _mill_bash mill
        |fi
        |""".stripMargin

    val homeDest = ".cache/mill/download/mill-completion.sh"
    def writeLoudly(path: os.Path, contents: String) = {
      println("Writing to " + path)
      os.write.over(path, contents)
    }
    writeLoudly(os.home / os.SubPath(homeDest), script)
    for (fileName <- Seq(".bash_profile", ".zshrc")) {
      val file = os.home / fileName
      val markerComment = "# MILL_SOURCE_COMPLETION"
      val prevLines =
        if (os.exists(file)) os.read.lines(file)
        else Nil

      val updated = prevLines
        .filter(!_.contains(markerComment))
        .++(Seq(s"source $homeDest $markerComment"))
        .mkString("\n")

      writeLoudly(file, updated)

    }
  }
}
