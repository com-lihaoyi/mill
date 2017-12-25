package mill.main

import ammonite.interp.Preprocessor
import ammonite.util.{Imports, Name, Util}

object CustomCodeWrapper extends Preprocessor.CodeWrapper {
  def top(pkgName: Seq[Name], imports: Imports, indexedWrapperName: Name) = {
    s"""
       |package ${pkgName.head.encoded}
       |package ${Util.encodeScalaSourcePath(pkgName.tail)}
       |$imports
       |import mill._
       |sealed abstract class ${indexedWrapperName.backticked} extends mill.Module{\n
       |""".stripMargin
  }


  def bottom(printCode: String, indexedWrapperName: Name, extraCode: String) = {
    val wrapName = indexedWrapperName.backticked
    val tmpName = ammonite.util.Name(indexedWrapperName.raw + "-Temp").backticked

    // Define `discovered` in the `tmpName` trait, before mixing in `MainWrapper`,
    // to ensure that `$tempName#discovered` is initialized before `MainWrapper` is.
    //
    // `import $wrapName._` is necessary too let Ammonite pick up all the
    // members of class wrapper, which are inherited but otherwise not visible
    // in the AST of the `$wrapName` object
    //
    // We need to duplicate the Ammonite predef as part of the wrapper because
    // imports within the body of the class wrapper are not brought into scope
    // by the `import $wrapName._`. Other non-Ammonite-predef imports are not
    // made available, and that's just too bad
    s"""
       |}
       |trait $tmpName{
       |  val discovered = mill.discover.Discovered.make[$wrapName]
       |  val interpApi = ammonite.interp.InterpBridge.value
       |}
       |
       |object $wrapName
       |extends $wrapName
       |with $tmpName
       |with mill.main.MainWrapper[$wrapName] {
       |  ${ammonite.main.Defaults.replPredef}
       |  ${ammonite.main.Defaults.predefString}
       |  ${ammonite.Main.extraPredefString}
       |  import ammonite.repl.ReplBridge.{value => repl}
       |  import ammonite.interp.InterpBridge.{value => interp}
       |  import $wrapName._
      """.stripMargin +
      Preprocessor.CodeWrapper.bottom(printCode, indexedWrapperName, extraCode)
  }
}
