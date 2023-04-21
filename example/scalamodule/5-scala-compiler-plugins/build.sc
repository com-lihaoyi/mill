import mill._, scalalib._

object foo extends RootModule with ScalaModule {
  def scalaVersion = "2.13.8"

  def compileIvyDeps = Agg(ivy"com.lihaoyi:::acyclic:0.3.6")
  def scalacOptions = Seq("-P:acyclic:force")
  def scalacPluginIvyDeps = Agg(ivy"com.lihaoyi:::acyclic:0.3.6")
}

//
// You can use Scala compiler plugins by setting `scalacPluginIvyDeps`. The above
// example also adds the plugin to `compileIvyDeps`, since that plugin's artifact
// is needed on the compilation classpath (though not at runtime).
//
// NOTE: Remember that compiler plugins are published against the full Scala
// version (eg. 2.13.8 instead of just 2.13), so when including them make sure to
// use the  `:::` syntax shown above in the example.

/** Usage

> ./mill compile
...
error: Unwanted cyclic dependency
error: ...src/Foo.scala...
error:   def y = Bar.z
error: ...src/Bar.scala...
error:   def x = Foo.y

*/
