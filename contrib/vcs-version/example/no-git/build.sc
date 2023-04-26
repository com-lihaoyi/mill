
import mill.vcs.version._
import mill._
import mill.define.Command

def baseDir = build.millSourcePath

def verify(): Command[Unit] =
  T.command {
    println("""sys.env("GIT_DIR") ["""  + sys.env("GIT_DIR") + "]")
    val vcState = VcsVersion.vcsState()
    T.log.errorStream.println(s"vcsState: ${vcState}")
    assert(vcState.vcs == None)

    val version = vcState.format()
    T.log.errorStream.println(s"version: ${version}")

    assert(version == "0.0.0-0-no-vcs", s"""Expected: "0.0.0-0-no-vcs", actual: "$version"""")
    ()
  }

/** Usage

> GIT_DIR=. ./mill verify

*/