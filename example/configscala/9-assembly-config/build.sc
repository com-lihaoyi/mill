// == Customizing the Assembly

import mill._, scalalib._
import mill.modules.Assembly._

object foo extends ScalaModule {
  def moduleDeps = Seq(bar)
  def scalaVersion = "2.13.8"
  def ivyDeps = Agg(ivy"com.lihaoyi::os-lib:0.9.1")
  def assemblyRules = Seq(
    Rule.Append("application.conf"), // all application.conf files will be concatenated into single file
    Rule.AppendPattern(".*\\.conf"), // all *.conf files will be concatenated into single file
    Rule.ExcludePattern(".*\\.temp"), // all *.temp files will be excluded from a final jar
    Rule.Relocate("shapeless.**", "shade.shapless.@1") // the `shapeless` package will be shaded under the `shade` package
  )

}

object bar extends ScalaModule {
  def scalaVersion = "2.13.8"
}

// When you make a runnable jar of your project with `assembly` command,
// you may want to exclude some files from a final jar (like signature files,
// and manifest files from library jars), and merge duplicated files (for
// instance `reference.conf` files from library dependencies).
//
// By default mill excludes all `+*.sf+`, `+*.dsa+`, `+*.rsa+`, and
// `META-INF/MANIFEST.MF` files from assembly, and concatenates all
// `reference.conf` files. You can also define your own merge/exclude rules.

/** Example Usage

> ./mill foo.assembly

> unzip -p ./out/foo/assembly.dest/out.jar application.conf
Bar Application Conf
Foo Application Conf

> java -jar ./out/foo/assembly.dest/out.jar\
Loaded application.conf from resources:
Foo Application Conf
Bar Application Conf

*/