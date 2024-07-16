// SNIPPET:BUILD
import mill._, javalib._

object foo extends RootModule with JavaModule {
  // You can have arbitrary numbers of third-party dependencies
  def ivyDeps = Agg(
    ivy"org.apache.commons:commons-text:1.12.0"
  )

  // Choose a main class to use for `.run` if there are multiple present
  def mainClass: T[Option[String]] = Some("foo.Foo2")

  // Add (or replace) source folders for the module to use
  def sources = T.sources{
    super.sources() ++ Seq(PathRef(millSourcePath / "custom-src"))
  }

  // Add (or replace) resource folders for the module to use
  def resources = T.sources{
    super.resources() ++ Seq(PathRef(millSourcePath / "custom-resources"))
  }

  // Generate sources at build time
  def generatedSources: T[Seq[PathRef]] = T {
    for(name <- Seq("A", "B", "C")) os.write(
      T.dest / s"Foo$name.java",
      s"""
         |package foo;
         |public class Foo$name {
         |  public static String value = "hello $name";
         |}
      """.stripMargin
    )

    Seq(PathRef(T.dest))
  }

  // Pass additional JVM flags when `.run` is called or in the executable
  // generated by `.assembly`
  def forkArgs: T[Seq[String]] = Seq("-Dmy.custom.property=my-prop-value")

  // Pass additional environmental variables when `.run` is called. Note that
  // this does not apply to running externally via `.assembly
  def forkEnv: T[Map[String, String]] = Map("MY_CUSTOM_ENV" -> "my-env-value")
}

// SNIPPET:FATAL_WARNINGS
//