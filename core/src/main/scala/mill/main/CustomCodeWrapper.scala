package mill.main

import ammonite.interp.Preprocessor
import ammonite.util.{Imports, Name, Util}

object CustomCodeWrapper extends Preprocessor.CodeWrapper {
  def top(pkgName: Seq[Name], imports: Imports, indexedWrapperName: Name) = {
    val wrapName = indexedWrapperName.backticked
    s"""
       |package ${pkgName.head.encoded}
       |package ${Util.encodeScalaSourcePath(pkgName.tail)}
       |$imports
       |import mill._
       |
       |object $wrapName extends $wrapName with mill.main.MainWrapper[$wrapName]{
       |  lazy val discovered = mill.discover.Discovered.make[$wrapName]
       |}
       |
       |sealed abstract class $wrapName extends mill.Module{
       |""".stripMargin
  }


  def bottom(printCode: String, indexedWrapperName: Name, extraCode: String) = {
    // We need to disable the `$main` method definition inside the wrapper
    // class, because otherwise it might get picked up by Ammonite and run as
    // a static class, which naturally blows up
    "\n}"
  }
}
