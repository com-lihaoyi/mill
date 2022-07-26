//> using mill.version "0.10.5"

//> using dep "de.tototec::de.tobiasroeser.mill.vcs.version::0.1.4"

// using file "build_deps.sc"
//> using file "Deps2.scala"

import $ivy.`de.tototec::de.tobiasroeser.mill.vcs.version::0.1.4`, de.tobiasroeser.mill.vcs.version.VcsVersion
import $file.build_deps

import mill._
import mill.scalalib._
import build_deps.Deps

object foo extends mill.define.Module {
  val ivyDeps = Agg(
    Deps.lambdatest
  )
}
