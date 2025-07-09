package mill.tabcomplete

import mill.*
import mill.api.{SelectMode, Result}
import mill.api.{Discover, Evaluator, ExternalModule}

import mainargs.arg

/**
 * Handles Bash and Zsh tab completions, which provide an array of tokens in the current
 * shell and the index of the token currently being completed.
 */
private[this] object TabCompleteModule extends ExternalModule {

  lazy val millDiscover = Discover[this.type]

  /**
   * The main entrypoint for Mill's Bash and Zsh tab-completion logic
   */
  def complete(
      ev: Evaluator,
      @arg(positional = true) index: Int,
      args: mainargs.Leftover[String]
  ) = Task.Command(exclusive = true) {

    val (query, unescapedOpt) = args.value.lift(index) match {
      // Zsh has the index pointing off the end of the args list, while
      // Bash has the index pointing at an empty string arg
      case None | Some("") => ("_", None)

      case Some(currentToken) =>
        val unescaped = currentToken.replace("\\", "").replace("\"", "").replace("\'", "")
        val trimmed = unescaped
          .take(unescaped.lastIndexWhere(c => !c.isLetterOrDigit && !"-_,".contains(c)) + 1)

        val query = trimmed.lastOption match {
          case None => "_"
          case Some('.') => trimmed + "_"
          case Some('[') => trimmed + "__]"
          case Some(',') => trimmed + "__]"
          case Some(']') => trimmed + "._"
        }

        (query, Some(unescaped))
    }

    ev.resolveSegments(Seq(query), SelectMode.Multi).map { res =>
      val unescapedStr = unescapedOpt.getOrElse("")
      val filtered = res.map(_.render).filter(_.startsWith(unescapedStr))
      val moreFiltered = unescapedOpt match {
        case Some(u) if filtered.contains(u) =>
          ev.resolveSegments(Seq(u + "._"), SelectMode.Multi) match {
            case Result.Success(v) => v.map(_.render)
            case Result.Failure(error) => Nil
          }

        case _ => Nil
      }

      (filtered ++ moreFiltered).foreach(println)
    }
  }

  /**
   * Installs the Mill tab completion script globally and hooks it into
   * `~/.zshrc` and `~/.bash_profile`. Can be passed an optional `--dest <path>`
   * to instead write it to a manually-specified destination path
   */
  def install(dest: os.Path = null) = Task.Command(exclusive = true) {
    val script = os.read(os.resource / "mill/tabcomplete/complete.sh")

    def writeLoudly(path: os.Path, contents: String) = {
      println("Writing to " + path)
      os.write.over(path, contents, createFolders = true)
    }
    dest match {
      case null =>
        val homeDest = ".cache/mill/download/mill-completion.sh"

        writeLoudly(os.home / os.SubPath(homeDest), script)
        for (fileName <- Seq(".bash_profile", ".zshrc")) {
          val file = os.home / fileName
          // We use the marker comment to help remove any previous `source` line before
          // adding a new line, so that running `install` over and over doesn't build up
          // repeated source lines
          val markerComment = "# MILL_SOURCE_COMPLETION_LINE"
          val prevLines =
            if (os.exists(file)) os.read.lines(file)
            else Nil

          val updated = prevLines
            .filter(!_.contains(markerComment))
            .++(Seq(s"source ~/$homeDest $markerComment\n"))
            .mkString("\n")

          writeLoudly(file, updated)
        }
        println(s"Please restart your shell or `source ~/$homeDest` to enable completions")

      case dest => writeLoudly(dest, script)
    }
  }
}
