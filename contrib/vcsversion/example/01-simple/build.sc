
import mill.vcs.version._
import mill._
import mill.define.Command

val baseDir = millSourcePath

def verify1(): Command[Unit] = T.command {
  val vcsState = VcsVersion.vcsState()
  assert(vcsState.vcs == Some(Vcs.git))

  val version = vcsState.format()
  assert(version.startsWith("1.2.3-1-") && !version.contains("DIRTY"))
  ()
}

def vcsFormat = T{ VcsVersion.vcsState().format() }

/** Usage

> git init .
> git add build.sc
> git commit -m "first commit"
> git tag 1.2.3
> git add plugins.sc
> git commit -m "second commit"

> ./mill verify1

> ./mill mill.vcs.version.VcsVersion/vcsState

> printf "\n// dummy text" >> plugins.sc

> ./mill show vcsFormat
"1.2.3-1-...-DIRTY..."

*/