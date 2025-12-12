package mill.tabcomplete

import mill.*
import mill.api.{Result, SelectMode}
import mill.api.{Discover, Evaluator, ExternalModule}
import mill.internal.MillCliConfig
import mainargs.{ArgSig, TokensReader, arg}
import mill.api.internal.Resolved

/**
 * Handles Bash and Zsh tab completions, which provide an array of tokens in the current
 * shell and the index of the token currently being completed.
 */
private object TabCompleteModule extends ExternalModule {

  lazy val millDiscover = Discover[this.type]

  /**
   * The main entrypoint for Mill's Bash and Zsh tab-completion logic
   */
  def complete(
      ev: Evaluator,
      @arg(positional = true) index: Int,
      args0: mainargs.Leftover[String]
  ) = Task.Command(exclusive = true)[Unit] {
    val args = args0.value
    def group(
        tokens: Seq[String],
        flattenedArgSigs: Seq[(ArgSig, TokensReader.Terminal[?])],
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

    val argSigs = flattenSigs(MillCliConfig.parser.main)

    val outputs: Seq[(String, String)] =
      group(args.drop(1), MillCliConfig.parser.main.flattenedArgSigs, true) match {

        // Initial parse succeeds, `leftoverArgs` contains either the task selector,
        // or the start of another flag
        case mainargs.Result.Success(v) =>
          val parsedArgCount = args.length - v.remaining.length
          // The cursor is after the task being run, we try to resolve the task to
          // see if it is a command with flags we can autocpmplete
          if (index > parsedArgCount) {
            val resolved = ev.resolveTasks(Seq(args(parsedArgCount)), SelectMode.Multi)

            val entrypointOpt = resolved match {
              case _: Result.Failure => None
              case Result.Success(ts) =>
                val entryPoints: Seq[mainargs.MainData[?, ?]] = ts.flatMap { t =>
                  ev
                    .rootModule
                    .moduleCtx
                    .discover
                    .resolveEntrypoint(t.ctx.enclosingCls, t.ctx.segments.last.value)
                }

                // If we find multiple entrypoints for the tasks selected, pick one arbitrarily
                entryPoints.headOption
            }

            entrypointOpt.map { ep =>
              val taskArgs = v.remaining.drop(1)
              val taskArgsIndex = index - parsedArgCount - 1

              val remaining = group(taskArgs, ep.flattenedArgSigs, false) match {
                case mainargs.Result.Success(grouping) => grouping.remaining
                case r: mainargs.Result.Failure.MismatchedArguments => r.unknown
                case other => throw Exception(s"Unexpected result: $other")
              }

              val commandParsedArgCount = v.remaining.length - remaining.length - 1
              if (taskArgsIndex == commandParsedArgCount) {
                findMatchingArgs(remaining.lift(taskArgsIndex), flattenSigs(ep))
                  .getOrElse(delegateToBash(args, index))
              } else delegateToBash(args, index)
            }.getOrElse(Nil)
          }

          // The cursor is before the task being run. It can't be an incomplete
          // `-f` or `--flag` because parsing succeeded, so delegate to file completion
          else if (index < parsedArgCount) {
            findMatchingArgs(args.lift(index), argSigs)
              .getOrElse(delegateToBash(args, index))
          }
          // This is the task I need to autocomplete, or the next incomplete flag
          else if (index == parsedArgCount) {
            findMatchingArgs(args.lift(index), argSigs)
              .getOrElse(completeTasks(ev, index, args)) ++
              delegateToBash(args, index)

          } else ???

        // Initial parse fails. Only failure mode is `incomplete`:
        //
        // - `missing` should be empty since all Mill flags have defaults
        // - `duplicate` should be empty since we use `allowRepeats = true`
        // - `unknown` should be empty since unknown tokens end up in `leftoverArgs`
        case _ =>
          // In this case, we cannot really identify any tokens in the argument list
          // which are task selectors, since the last flag is incomplete and prior
          // flags are all completed. So just delegate to bash completion
          findMatchingArgs(args.lift(index), argSigs).getOrElse(Nil) ++
            delegateToBash(args, index)

      }

    val prefixes = outputs.map(_._1)
    val offset = prefixes.map(_.length).maxOption.getOrElse(0) + 2

    val res = outputs match {
      // When there is only one output and it has a description, trim off the description
      // and include it as a second output. This ensures that shells like Bash won't
      // insert the description at the prompt, since Bash doesn't have the ability to trim
      // them off automatically, and also ensures the description gets printed so the
      // user can read it even if there's only one output
      //
      // https://stackoverflow.com/a/10130007/871202
      case Seq((prefix, suffix)) if suffix.nonEmpty => Seq(prefix, prefix + ": " + suffix)
      case _ =>
        for ((prefix, suffix) <- outputs) yield {
          if (suffix.isEmpty) prefix
          else s"$prefix${" " * (offset - prefix.length)}$suffix"
        }
    }

    res.foreach(println)
  }

  def flattenSigs(ep: mainargs.MainData[?, ?]) = ep.flattenedArgSigs.map(_._1)

  def findMatchingArgs(
      stringOpt: Option[String],
      argSigs: Seq[mainargs.ArgSig]
  ): Option[Seq[(String, String)]] = {
    def findMatchArgs0(
        prefix: String,
        nameField: ArgSig => Option[String]
    ): Option[Seq[(String, String)]] = {
      val res = for (arg <- argSigs if !arg.positional) yield {
        if (
          stringOpt.exists(_.startsWith(prefix)) &&
          nameField(arg).zip(stringOpt).exists((n, s) => (prefix + n).startsWith(s))
        ) {

          val typeStringPrefix = arg.reader match {
            case s: mainargs.TokensReader.ShortNamed[_] => s"<${s.shortName}> "
            case _ => ""
          }

          for (
            name <- nameField(arg)
            // We don't want complete outdated args, although we still support them
            if !MillCliConfig.isUnsupported(arg) && !MillCliConfig.isDeprecated(arg)
          ) yield {
            val suffix =
              val docLine = oneLine(arg.doc.getOrElse(""))
              s"$typeStringPrefix$docLine"
            (s"$prefix$name" -> suffix)
          }
        } else Nil

      }

      Option.when(res.flatten.nonEmpty) {
        res.flatten
      }
    }

    findMatchArgs0("--", _.longName(mainargs.Util.kebabCaseNameMapper))
      .orElse(findMatchArgs0("-", _.shortName.map(_.toString)))
  }

  def delegateToBash(args: Seq[String], index: Int) = {
    val res = os.call(
      (
        "bash",
        "-c",
        "compgen -f -- " + args.lift(index).map(pprint.Util.literalize(_)).getOrElse("\"\"")
      ),
      check = false
    )

    res.out.lines().map((_, ""))
  }

  def oneLine(txt: String) =
    if (txt == "") ""
    else {
      txt
        // People often forget trailing periods when there's a paragraph break (double newline), so
        // mangle the text to add one if necessary so it looks good when combined onto a single line
        .replaceAll("([a-zA-Z0-9_-])\n\n", "$1.\n\n")
        .linesIterator
        .map(_.trim)
        // Drop empty lines, so if there are multiple paragraphs they get combined into one
        .filter(_.nonEmpty)
        .mkString(" ")
    }

  def getDocs(resolved: Resolved) = {
    val allDocs: Iterable[String] = resolved match {
      case _: Resolved.Module =>
        mill.util.Inspect.scaladocForModule(resolved.cls)
      case _ =>
        mill.util.Inspect.scaladocForTask(resolved.fullSegments, resolved.cls)
    }

    oneLine(allDocs.mkString("\n"))
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
          case Some('/') => trimmed + "_"
          case Some(_) => trimmed + "_"
        }

        (query, Some(unescaped))
    }

    // Check if user is typing with bracket syntax
    val useBracketSyntax = unescapedOpt.exists(u => u.contains("[") || u.contains(","))

    ev.resolveRaw(Seq(query), SelectMode.Multi).map { res =>
      val unescapedStr = unescapedOpt.getOrElse("")
      val filtered = res.flatMap { r =>
        val rendered = renderSegments(r.fullSegments, useBracketSyntax)
        Option.when(rendered.startsWith(unescapedStr))(rendered -> getDocs(r))
      }
      val moreFiltered = unescapedOpt match {
        case Some(u) if filtered.exists(_._1 == u) =>
          ev.resolveRaw(Seq(u + "._"), SelectMode.Multi) match {
            case Result.Success(v) =>
              v.map { res => renderSegments(res.fullSegments, useBracketSyntax) -> getDocs(res) }
            case _: Result.Failure => Nil
          }

        case _ => Nil
      }

      filtered ++ moreFiltered
    }.toOption.getOrElse(Nil)
  }

  /**
   * Renders segments for tab completion. By default uses the new dot-delimited syntax
   * for cross modules (e.g., `foo.2_12_20` instead of `foo[2.12.20]`), but uses bracket
   * syntax if the user has already started typing with brackets.
   */
  def renderSegments(segments: mill.api.daemon.Segments, useBracketSyntax: Boolean): String = {
    if (useBracketSyntax) segments.render
    else segments.render
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
