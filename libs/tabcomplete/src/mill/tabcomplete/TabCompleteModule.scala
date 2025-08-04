package mill.tabcomplete

import mill.*
import mill.api.{Result, SelectMode}
import mill.api.{Discover, Evaluator, ExternalModule}
import mill.internal.MillCliConfig
import mainargs.{ArgSig, TokensReader, arg}

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
  ) = Task.Command(exclusive = true)[Unit] {
    def group(
        tokens: Seq[String],
        flattenedArgSigs: Seq[(ArgSig, TokensReader.Terminal[_])],
        allowLeftover: Boolean
    ) = {
      mainargs.TokenGrouping.groupArgs(
        tokens,
        flattenedArgSigs,
        allowPositional = false,
        allowRepeats = true,
        allowLeftover = allowLeftover,
        nameMapper = mainargs.Util.kebabCaseNameMapper
      )
    }

    group(args.value.drop(1), MillCliConfig.parser.main.flattenedArgSigs, true) match {
      // Initial parse fails. Only failure mode is `incomplete`:
      //
      // - `missing` should be empty since all Mill flags have defaults
      // - `duplicate` should be empty since we use `allowRepeats = true`
      // - `unknown` should be empty since unknown tokens end up in `leftoverArgs`
      case mainargs.Result.Failure.MismatchedArguments(Nil, unknown, Nil, Some(_)) =>
        // In this case, we cannot really identify any tokens in the argument list
        // which are task selectors, since the last flag is incomplete and prior
        // flags are all completed. So just delegate to bash completion
        delegateToBash(args, index)

      // Initial parse succeeds, `leftoverArgs` contains either the task selector,
      // or the start of another flag
      case mainargs.Result.Success(v) =>
        val parsedArgCount = args.value.length - v.remaining.length

        // The cursor is after the task being run, we don't know anything about those
        // flags so delegate to bash completion
        if (index > parsedArgCount) {
          val resolved = ev.resolveTasks(Seq(args.value(parsedArgCount)), SelectMode.Multi)

          resolved match {
            case _: Result.Failure => delegateToBash(args, index)
            case Result.Success(ts) =>
              val entryPoints: Seq[mainargs.MainData[_, _]] = ts.flatMap { t =>
                ev
                  .rootModule
                  .moduleCtx
                  .discover
                  .resolveEntrypoint(t.ctx.enclosingCls, t.ctx.segments.last.value)
              }
              for (ep <- entryPoints.headOption) {
                val taskArgs = v.remaining.drop(1)
                val taskArgsIndex = index - parsedArgCount - 1

                def handleRemaining(remaining: Seq[String]) = {
                  val commandParsedArgCount = v.remaining.length - remaining.length - 1
                  if (taskArgsIndex == commandParsedArgCount) {
                    if (!findMatchingArgs(remaining.lift(taskArgsIndex), ep.flattenedArgSigs.map(_._1))) {
                      delegateToBash(args, index)
                    }
                  } else delegateToBash(args, index)
                }

                group(taskArgs, ep.flattenedArgSigs, false) match {
                  case mainargs.Result.Success(grouping) => handleRemaining(grouping.remaining)
                  case mainargs.Result.Failure.MismatchedArguments(
                        missing,
                        unknown,
                        duplicate,
                        incomplete
                      ) =>
                    handleRemaining(unknown)
                }
              }

          }

        }
        // The cursor is before the task being run. It can't be an incomplete
        // `-f` or `--flag` because parsing succeeded, so delegate to file completion
        else if (index < parsedArgCount) {
          val argSigs = MillCliConfig.parser.main.flattenedArgSigs.map(_._1)
          if (!findMatchingArgs(args.value.lift(index), argSigs)) delegateToBash(args, index)
        }
        // This is the task I need to autocomplete, or the next incomplete flag
        else if (index == parsedArgCount) {
          val argSigs = MillCliConfig.parser.main.flattenedArgSigs.map(_._1)
          if (!findMatchingArgs(args.value.lift(index), argSigs)) completeTasks(ev, index, args.value)
        } else ???
    }
  }

  def findMatchingArgs(stringOpt: Option[String], argSigs: Seq[mainargs.ArgSig]): Boolean = {
    def findMatchArgs0(prefix: String, nameField: ArgSig => Option[String]): Boolean = {
      if (stringOpt.exists(_.startsWith(prefix))) {
        for (arg <- argSigs if !arg.positional) {
          val typeStringPrefix = arg.reader match{
            case s: mainargs.TokensReader.ShortNamed[_] => s"<${s.shortName}> "
            case _ => ""
          }
          for (name <- nameField(arg) if !arg.doc.contains("Unsupported")) {
            val str = s"$prefix$name:$typeStringPrefix${arg.doc.getOrElse("").trim.linesIterator.next()}"
            mill.constants.DebugLog.println(str)
            println(str)
          }
        }
        true
      } else false
    }
    findMatchArgs0("--", _.longName(mainargs.Util.kebabCaseNameMapper)) ||
    findMatchArgs0("-", _.shortName.map(_.toString))
  }

  def delegateToBash(args: mainargs.Leftover[String], index: Int) = {
    val res = os.call(
      (
        "bash",
        "-c",
        "compgen -f -- " + args.value.lift(index).map(pprint.Util.literalize(_)).getOrElse("\"\"")
      ),
      check = false
    )

    res.out.lines().foreach(println)
  }

  def completeTasks(ev: Evaluator, index: Int, args: Seq[String]) = {

    val (query, unescapedOpt) = args.lift(index) match {
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
        for (fileName <- Seq(".bash_profile", ".zshrc", ".bashrc")) {
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
