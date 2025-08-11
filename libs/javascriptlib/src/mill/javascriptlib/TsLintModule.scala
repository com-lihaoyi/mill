package mill.javascriptlib

import mill.*
import mill.api.Result
import scala.util.{Try, Success, Failure}
import os.*
import mill.api.BuildCtx
trait TsLintModule extends Module {
  sealed trait Lint
  private case object Eslint extends Lint
  private case object Prettier extends Lint

  def npmLintDeps: T[Seq[String]] = Task {
    Seq(
      "prettier@3.4.2",
      "eslint@9.18.0",
      "typescript-eslint@8.21.0",
      "@eslint/js@9.18.0"
    )
  }

  private def npmInstallLint: T[PathRef] = Task {
    Try(os.copy.over(BuildCtx.workspaceRoot / ".npmrc", Task.dest / ".npmrc")).getOrElse(())
    os.call((
      "npm",
      "install",
      "--prefix",
      ".",
      "--userconfig",
      ".npmrc",
      "--save-dev",
      npmLintDeps()
    ))
    PathRef(Task.dest)
  }

  // Handle config - prioritize eslint config
  private def fmtConfig: T[Seq[PathRef]] = Task.Sources(
    BuildCtx.workspaceRoot / "eslint.config.mjs",
    BuildCtx.workspaceRoot / "eslint.config.cjs",
    BuildCtx.workspaceRoot / "eslint.config.js",
    BuildCtx.workspaceRoot / ".eslintrc.js",
    BuildCtx.workspaceRoot / ".eslintrc.mjs",
    BuildCtx.workspaceRoot / ".eslintrc.cjs",
    BuildCtx.workspaceRoot / ".prettierrc",
    BuildCtx.workspaceRoot / ".prettierrc.json"
  )

  private def resolvedFmtConfig: Task[Lint] = Task.Anon {
    val locs = fmtConfig()

    val lintT: Path => Lint = _.last match {
      case s if s.contains("eslint.config") => Eslint
      case _ => Prettier
    }

    locs.find(p => os.exists(p.path)) match {
      case None =>
        Task.fail(
          s"Lint couldn't find an eslint.config.(js|mjs|cjs) or a .eslintrc.(js|mjs|cjs) or a `.pretiierrc` file"
        )
      case Some(c) => lintT(c.path)
    }
  }

  // eslint
  def checkFormatEslint(args: mill.api.Args): Command[Unit] = Task.Command {
    val _ = args // silence unused parameter warning

    resolvedFmtConfig() match {
      case Eslint =>
        val cwd = BuildCtx.workspaceRoot
        os.symlink(cwd / "node_modules", npmInstallLint().path / "node_modules")
        val eslint = npmInstallLint().path / "node_modules/.bin/eslint"
        val logPath = npmInstallLint().path / "eslint.log"
        val result =
          Try {
            os.call(
              (eslint, "."),
              stdout = os.PathRedirect(logPath),
              stderr = os.PathRedirect(logPath),
              cwd = cwd
            )
          }

        val replacements = Seq(
          s"$cwd/" -> "",
          "potentially fixable with the `--fix` option" ->
            s"potentially fixable with running ${moduleDir.last}.reformatAll"
        )

        os.remove(cwd / "node_modules")
        result match {
          case Failure(e: os.SubprocessException) if e.result.exitCode == 1 =>
            val lines = os.read.lines(logPath)
            val logMssg = lines.map(line =>
              replacements.foldLeft(line) { case (currentLine, (target, replacement)) =>
                currentLine.replace(target, replacement)
              }
            )
            println(logMssg.mkString("\n"))
          case Failure(e: os.SubprocessException) =>
            println(s"Eslint exited with code: ${e.result.exitCode}")
            println(os.read.lines(logPath).mkString("\n"))
          case Failure(_) =>
            println(os.read.lines(logPath).mkString("\n"))
          case Success(_) => println("All matched files use Eslint code style!")
        }
      case _ =>
    }
  }

  def reformatEslint(args: mill.api.Args): Command[Unit] = Task.Command {
    val _ = args // silence unused parameter warning, part of API

    resolvedFmtConfig() match {
      case Eslint =>
        val cwd = BuildCtx.workspaceRoot
        os.symlink(cwd / "node_modules", npmInstallLint().path / "node_modules")
        val eslint = npmInstallLint().path / "node_modules/.bin/eslint"
        val logPath = npmInstallLint().path / "eslint.log"

        val result =
          Try {
            os.call(
              (eslint, ".", "--fix"),
              stdout = os.PathRedirect(logPath),
              stderr = os.PathRedirect(logPath),
              cwd = cwd
            )
          }

        os.remove(cwd / "node_modules")
        result match {
          case Failure(e: os.SubprocessException) =>
            println(s"Eslint exited with code: ${e.result.exitCode}")
            println(os.read.lines(logPath).mkString("\n"))
          case Failure(_) =>
            println(os.read.lines(logPath).mkString("\n"))
          case Success(_) => println("All matched files have been reformatted!")
        }
      case _ =>
    }
  }

  // prettier
  def checkFormatPrettier(args: mill.api.Args): Command[Unit] = Task.Command {
    resolvedFmtConfig() match {
      case Prettier =>
        val cwd = BuildCtx.workspaceRoot
        val prettier = npmInstallLint().path / "node_modules/.bin/prettier"
        val logPath = npmInstallLint().path / "prettier.log"
        val defaultArgs = if (args.value.isEmpty) Seq("*/**/*.ts") else args.value
        val userPrettierIgnore = os.exists(cwd / ".prettierignore")
        if (!userPrettierIgnore) os.symlink(cwd / ".prettierignore", prettierIgnore().path)
        val result =
          Try {
            os.call(
              (prettier, "--check", defaultArgs), // todo: collect from command line?
              stdout = os.Inherit,
              stderr = os.PathRedirect(logPath),
              cwd = cwd
            )
          }

        if (!userPrettierIgnore) os.remove(cwd / ".prettierignore")
        result match {
          case Failure(e: os.SubprocessException) if e.result.exitCode == 1 =>
            val lines = os.read.lines(logPath)
            val logMssg = lines.map(_.replace(
              "[warn] Code style issues found in the above file. Run Prettier with --write to fix.",
              s"[warn] Code style issues found. Run ${moduleDir.last}.reformatAll to fix."
            ))
            println(logMssg.mkString("\n"))
          case Failure(e: os.SubprocessException) if e.result.exitCode == 2 =>
            println(os.read.lines(logPath).mkString("\n"))
          case Failure(e: os.SubprocessException) =>
            println(s"Prettier exited with code: ${e.result.exitCode}")
            println(os.read.lines(logPath).mkString("\n"))
          case Failure(_) =>
            println(os.read.lines(logPath).mkString("\n"))
          case Success(_) =>
        }
      case _ =>
    }

  }

  def reformatPrettier(args: mill.api.Args): Command[Unit] = Task.Command {
    resolvedFmtConfig() match {
      case Prettier =>
        val cwd = BuildCtx.workspaceRoot
        val prettier = npmInstallLint().path / "node_modules/.bin/prettier"
        val logPath = npmInstallLint().path / "prettier.log"
        val defaultArgs = if (args.value.isEmpty) Seq("*/**/*.ts") else args.value
        val userPrettierIgnore = os.exists(cwd / ".prettierignore")
        if (!userPrettierIgnore) os.symlink(cwd / ".prettierignore", prettierIgnore().path)
        val result =
          Try {
            os.call(
              (prettier, "--write", defaultArgs), // todo: collect from command line?
              stdout = os.Inherit,
              stderr = os.PathRedirect(logPath),
              cwd = cwd
            )
          }

        if (!userPrettierIgnore) os.remove(cwd / ".prettierignore")
        result match {
          case Failure(e: os.SubprocessException) =>
            println(s"Prettier exited with code: ${e.result.exitCode}")
            println(os.read.lines(logPath).mkString("\n"))
          case Failure(_) =>
            println(os.read.lines(logPath).mkString("\n"))
          case Success(_) => println("All matched files have been reformatted!")
        }
      case _ =>
    }
  }

  private def prettierIgnore: T[PathRef] = Task {
    val config = Task.dest / ".prettierignore"
    val content =
      s"""|node_modules
          |.git
          |out
          |""".stripMargin
    os.write.over(config, content)

    PathRef(config)
  }

  def checkFormatAll(args: mill.api.Args): Command[Unit] = Task.Command {
    checkFormatEslint(args)()
    checkFormatPrettier(args)()
  }

  def reformatAll(args: mill.api.Args): Command[Unit] = Task.Command {
    reformatEslint(args)()
    reformatPrettier(args)()
  }

}
