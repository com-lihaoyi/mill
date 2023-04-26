
import mill.vcs.version._
import mill._
import mill.define.Command

def baseDir = build.millSourcePath

def initVcs: T[Unit] =
  T {
    if (!os.exists(baseDir / ".git")) {
      T.log.info("Initializing git repo...")
      Seq(
        os.proc("git", "init", "."),
        os.proc("git", "add", "build.sc"),
        os.proc("git", "commit", "-m", "first commit"),
        os.proc("git", "add", "plugins.sc"),
        os.proc("git", "commit", "-m", "second commit")
      ) foreach (_.call(cwd = baseDir))
    }
    ()
  }

def verify(): Command[Unit] =
  T.command {
    initVcs()
    val vcState = VcsVersion.vcsState()

    val version = vcState.format()
    T.log.outputStream.println(s"format() should use the noTagFallback string. Actual: $version")
    assert(version.startsWith("0.0.0-2-"))

    val version2 = vcState.format(noTagFallback = "0.0.0")
    T.log.outputStream.println(s"""format(noTagFallback = "0.0.0") should use the noTagFallback string. Actual: ${version2}""")
    assert(version2.startsWith("0.0.0-2-"))

    val version3 = vcState.format(noTagFallback = "dev")
    T.log.outputStream.println(s"""format(noTagFallback = "dev") should use the noTagFallback string. Actual: ${version3}""")
    assert(version3.startsWith("dev-2-"))

    ()
  }

/** Usage

> ./mill verify

*/