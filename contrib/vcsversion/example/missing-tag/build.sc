
import mill.vcsversion._
import mill._
import mill.define.Command

def baseDir = millSourcePath

def vcsFormat = T{ VcsVersion.vcsState().format() }

def vcsFormat0 = T{ VcsVersion.vcsState().format(noTagFallback = "0.0.0") }

def vcsFormatDev = T{ VcsVersion.vcsState().format(noTagFallback = "dev") }

/** Usage

> git init .
> git add build.sc
> git commit -m "first commit"
> git add plugins.sc
> git commit -m "second commit"


> ./mill show vcsFormat
"0.0.0-2-..."

> ./mill show vcsFormat0
"0.0.0-2-..."

> ./mill show vcsFormatDev
"dev-2-..."

*/