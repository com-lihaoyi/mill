/**
 * API documentation for the Mill JVM build tool. This package contains all the Mill APIs
 * exposed for you to use in your `build.mill` and `package.mill` files. Language-agnostic
 * APIs are mostly in [[mill.api]] and [[mill.util]], while `*lib` packages like [[mill.javalib]],
 * [[mill.scalalib]], and [[mill.kotlinlib]] contain the language-specific toolchains.
 */
package object mill {

  /**
   * Process arguments must not depend on the cwd in which an `os.Path` happened to be converted.
   * `os.proc` converts its arguments before `.call(cwd = ...)` is evaluated, so a reproducible
   * `../mill-workspace` alias can otherwise be invalid for the subprocess's actual cwd.
   */
  implicit def pathToShellable(path: os.Path): os.Shellable =
    os.Shellable(Seq(mill.api.PathRef.toAbsString(path)))
}
