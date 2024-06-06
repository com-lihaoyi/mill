import mill._, scalalib._
import mill.scalalib.Assembly._

trait Jpackage extends JavaModule {
  def jpackageName: T[String] = artifactName
  def jpackage: T[PathRef] = T {
    val localCp = localClasspath().map(_.path)
    val runCp = runClasspath().map(_.path)
    val mainJar = jar().path
    val cp = runCp.filterNot(localCp.contains) ++ Seq(mainJar)

    val libs = T.dest / "lib"
    cp.filter(os.exists).foreach { p =>
      os.copy.into(p, libs, createFolders = true)
    }
    val appName = jpackageName()
    val outDest = T.dest / "out"
    os.makeDir.all(outDest)
    os.proc("jpackage", "--type", "app-image", "--name", appName, "--input", libs, "--main-jar", mainJar.last).call(cwd = outDest)
    PathRef(outDest / appName / "bin" / appName)
  }
}

object foo extends ScalaModule with Jpackage {
  def moduleDeps = Seq(bar)
  def scalaVersion = "2.13.8"
  def ivyDeps = Agg(ivy"com.lihaoyi::os-lib:0.9.1")
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

/** Usage

> ./mill foo.assembly

> unzip -p ./out/foo/assembly.dest/out.jar application.conf
Bar Application Conf
Foo Application Conf

> java -jar ./out/foo/assembly.dest/out.jar\
Loaded application.conf from resources:...
...Foo Application Conf
...Bar Application Conf

> mill show foo.jpackage
".../out/foo/jpackage.dest/out/foo/bin/foo"

> ./out/foo/jpackage.dest/out/foo/bin/foo
Loaded application.conf from resources: Foo Application Conf

*/