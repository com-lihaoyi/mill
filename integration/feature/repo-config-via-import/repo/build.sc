// Line under test: add the given repo as Maven repo to the configured repositories
import $repo.`file:///tmp/testrepo`

import mill._, mill.scalalib._

object foo extends JavaModule
