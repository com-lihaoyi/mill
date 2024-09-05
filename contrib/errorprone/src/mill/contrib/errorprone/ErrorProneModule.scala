package mill.contrib.errorprone

import mill.api.PathRef
import mill.{Agg, T}
import mill.scalalib.{Dep, DepSyntax, JavaModule}

import java.io.File

trait ErrorProneModule extends JavaModule {
  def errorProneVersion: T[String] = T.input {
    BuildInfo.errorProneVersion
  }
  def errorProneDeps: T[Agg[Dep]] = T {
    Agg(
      ivy"com.google.errorprone:error_prone_core:${errorProneVersion()}"
    )
  }
  def errorProneClasspath: T[Agg[PathRef]] = T {
    resolveDeps(T.task { errorProneDeps().map(bindDependency()) })()
  }

  def errorProneJavacEnableOptions: T[Seq[String]] = T {
    val processorPath = errorProneClasspath().map(_.path).mkString(File.pathSeparator)
    val enableOpts = Seq(
      "-XDcompilePolicy=simple",
      "-processorpath",
      processorPath,
      (Seq("-Xplugin:ErrorProne") ++ errorProneOptions()).mkString(" ")
    )
    val java17Options = Option.when(scala.util.Properties.isJavaAtLeast(16))(Seq(
      "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
      "--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
      "--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED"
    ).map(o => s"-J${o}")).toSeq.flatten
    java17Options ++ enableOpts
  }

  def errorProneOptions: T[Seq[String]] = T { Seq.empty[String] }

  override def javacOptions: T[Seq[String]] = T {
    val supOpts = super.javacOptions()
    val enableOpts = Option
      .when(!supOpts.exists(o => o.startsWith("-Xplugin:ErrorProne")))(
        errorProneJavacEnableOptions()
      )
    supOpts ++ enableOpts.toSeq.flatten
  }
}
