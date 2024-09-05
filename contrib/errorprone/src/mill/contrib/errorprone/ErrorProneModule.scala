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
    val baseOpts = Seq(
      "-XDcompilePolicy=simple",
      "-processorpath",
      processorPath,
      "-Xplugin:ErrorProne"
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
    ).map(o => s"-J${o}"))
    baseOpts ++ java17Options.toSeq.flatten
  }
  override def javacOptions: T[Seq[String]] = T {
    val supOpts = super.javacOptions()
    val additionalOpts = Option
      .when(!supOpts.exists(o => o.startsWith("-Xplugin:ErrorProne")))(
        errorProneJavacEnableOptions()
      )
    supOpts ++ additionalOpts.toSeq.flatten
  }
}
