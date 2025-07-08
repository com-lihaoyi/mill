/**
 * API documentation for the Mill JVM build tool. This package contains all the Mill APIs
 * exposed for you to use in your `build.mill` and `package.mill` files. Language-agnostic
 * APIs are mostly in [[mill.api]] and [[mill.util]], while `*lib` packages like [[mill.javalib]],
 * [[mill.scalalib]], and [[mill.kotlinlib]] contain the language-specific toolchains.
 */
package object mill extends mill.api.JsonFormatters with mill.util.TokenReaders0 {
  export api.Task.Simple as T
  export mill.api.PathRef
  export mill.api.Module
  export mill.api.Cross
  export mill.api.Args
  export mill.api.Task
  export mill.api.Task.Command
  export mill.api.Task.Worker
  export mill.api.TaskModule
}
