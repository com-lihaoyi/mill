/**
 * API documentation for the Mill JVM build tool. This package contains all the Mill APIs
 * exposed for you to use in your `build.mill` and `package.mill` files. Language-agnostic
 * APIs are mostly in `mill.api` and `mill.util`, while `*lib` packages like `mill.javalib`,
 * `mill.scalalib`, and `mill.kotlinlib` contain the language-specific toolchains.
 */
package object mill extends mill.api.JsonFormatters with mill.util.TokenReaders0 {
  type T[+T] = api.Task.Simple[T]
  export mill.api.PathRef
  export mill.api.Module
  export mill.api.Cross

  @deprecated("Use Seq[T] instead", "Mill 0.13.0-M1")
  type Agg[T] = Seq[T]
  @deprecated("Use Seq instead", "Mill 0.13.0-M1")
  val Agg = Seq

  export mill.api.Args
  export mill.api.Task
  export mill.api.Task.Command
  export mill.api.Task.Worker
  export mill.api.TaskModule

  type Source = api.Task.Simple[PathRef]
  type Sources = api.Task.Simple[Seq[PathRef]]

}
