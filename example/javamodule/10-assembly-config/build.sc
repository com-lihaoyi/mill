//// SNIPPET:BUILD
import mill._, javalib._
import mill.javalib.Assembly._

object foo extends JavaModule {
  def moduleDeps = Seq(bar)
  def assemblyRules = Seq(
    // all application.conf files will be concatenated into single file
    Rule.Append("application.conf"),
    // all *.conf files will be concatenated into single file
    Rule.AppendPattern(".*\\.conf"),
    // all *.temp files will be excluded from a final jar
    Rule.ExcludePattern(".*\\.temp"),
    // the `shapeless` package will be shaded under the `shade` package
    Rule.Relocate("shapeless.**", "shade.shapless.@1")
  )
}

object bar extends JavaModule {
}
