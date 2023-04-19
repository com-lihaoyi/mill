// == Compilation & Execution Flags

import mill._, scalalib._

object foo extends RootModule with ScalaModule{
  def scalaVersion = "2.13.8"
  def scalacOptions = Seq("-Ydelambdafy:inline")
  def forkArgs = Seq("-Xmx4g", "-Dmy.jvm.property=hello")
  def forkEnv = Map("MY_ENV_VAR" -> "WORLD")
}

// You can pass flags to the Scala compiler via `scalacOptions`. By default,
// `run` runs the compiled code in a subprocess, and you can pass in JVM flags
// via `forkArgs` or environment-variables via `forkEnv`.
//
// You can also run your code via
//
// [source,bash]
// ----
// mill foo.runLocal
// ----
//
// Which runs it in-process within an isolated classloader. This may be faster
// since you avoid the JVM startup, but does not support `forkArgs` or `forkEnv`.
//
// If you want to pass main-method arguments to `run` or `runLocal`, simply pass
// them after the `foo.run`/`foo.runLocal`:
//
// [source,bash]
// ----
// mill foo.run arg1 arg2 arg3
// mill foo.runLocal arg1 arg2 arg3
// ----

/* Example Usage

> ./mill run
hello WORLD

*/
