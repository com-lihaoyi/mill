
import mill.vcs.version._
import mill._
import mill.define.Command

def baseDir = build.millSourcePath

def vcsState = T{ VcsVersion.vcsState().vcs }

def vcsFormat = T{ VcsVersion.vcsState().format() }

/** Usage

> GIT_DIR=. ./mill show vcsState
[
]

> GIT_DIR=. ./mill show vcsFormat
"0.0.0-0-no-vcs"

*/