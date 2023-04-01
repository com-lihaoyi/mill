import mill._, scalalib._

object foo extends BuildModule with ScalaModule {
  def scalaVersion = "2.13.2"
  def ivyDeps = Agg(ivy"com.lihaoyi::scalatags:0.8.2")
}

// Most tasks in Mill builds are `Target`s, defined using the `T{...}` or
// `T[V]` syntax. A target caches its output on disk, re-using the cache if the
// inputs didn't change, discarding and re-computing the cache if the inputs
// did. This is the most common case, but not the only one.
//
// Other key task types include:
//
// 1. `T.input`: these re-compute their value every time. Useful for wrapping
//    external commands like `git rev-parse HEAD` to ensure that any changes
//    in the output of those commands is always picked up by the build
//
// 2. `T.persistent`:
//
// 3. `T.worker`: these preserve some in-memory state between runs. These are
//    useful for managing stateful caches or stateful external sub-processes,
//    for which preserving in-memory caches can greatly improve performance
//



/* Example Usage


> ./mill resolve _

*/
