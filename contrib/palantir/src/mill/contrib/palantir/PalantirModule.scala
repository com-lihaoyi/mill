package mill
package contrib.palantir

import mill.api.{Loose, PathRef}
import mill.scalalib.{DepSyntax, JavaModule}

trait PalantirModule extends JavaModule {

  def palantirClasspath: T[Loose.Agg[PathRef]] = T {
    defaultResolver().resolveDeps(
      Agg(ivy"com.palantir.javaformat:palantir-java-format:${palantirVersion()}")
    )
  }

  def palantirOptions: T[PalantirOptions] = T {
    PalantirOptions()
  }

  /**
   * Palantir version. Defaults to `2.50.0`.
   */
  def palantirVersion: T[String] = T {
    "2.50.0"
  }
}
object PalantirModule {

  // https://github.com/palantir/palantir-java-format/issues/548
  val jvmArgs: Seq[String] = Seq(
    "--add-exports",
    "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
    "--add-exports",
    "jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
    "--add-exports",
    "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
    "--add-exports",
    "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
    "--add-exports",
    "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"
  )

  def mainArgs(
      sources: IterableOnce[os.Path],
      options: PalantirOptions,
      check: Boolean = false
  ): Seq[String] = {

    val args = Seq.newBuilder[String]

    if (check) {
      // do not overwrite files and exit(1) if formatting changes were detected
      args += "--dry-run" += "--set-exit-if-changed"
    } else {
      // format files in place
      args += "--replace"
    }

    options.styleOverride.foreach(style => args += s"--$style")
    if (options.skipSortImports) args += "--skip-sorting-imports"
    if (options.skipUnusedImports) args += "--skip-removing-unused-imports"
    if (options.skipReflowingLongStrings) args += "--skip-reflowing-long-strings"

    // files must be last in arg list
    args ++=
      sources
        .iterator
        .flatMap(os.walk(_, includeTarget = true))
        .filter(os.isFile)
        .filter(_.ext == "java")
        .map(_.toIO.getAbsolutePath)

    args.result()
  }

  val mainClass: String = "com.palantir.javaformat.java.Main"
}
