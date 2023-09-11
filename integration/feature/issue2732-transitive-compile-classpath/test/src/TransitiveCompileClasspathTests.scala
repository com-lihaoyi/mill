package mill.integration
package local
import mill.bsp.Constants
import utest._

object TransitiveCompileClasspathTests extends IntegrationTestSuite {
  def tests: Tests = Tests {
    test("bar.compileClasspath is consistent") {
      val workspacePath = initWorkspace()
      assert(eval("bar.compileClasspath"))
      val file = workspacePath / "out" / "bar" / "compileClasspath.json"
      assert(os.exists(file))
      val cached = upickle.default.read[mill.eval.Evaluator.Cached](os.read(file))
      val readModules =
        upickle.default.read[Seq[mill.api.PathRef]](cached.value).map(_.path.toString)
      val expectedModules = Seq(
        os.sub / "foo" / "compile-resources",
        os.sub / "out" / "foo" / "compile.dest" / "classes",
        os.sub / "bar" / "compile-resources",
        os.sub / "repo1.maven.org" / "maven2" / "org" / "typelevel" / "cats-core_2.13" / "2.9.0" / "cats-core_2.13-2.9.0.jar",
        os.sub / "repo1.maven.org" / "maven2" / "org" / "scala-lang" / "scala-library" / "2.13.12" / "scala-library-2.13.12.jar",
        os.sub / "repo1.maven.org" / "maven2" / "io" / "circe" / "circe-core_2.13" / "0.14.0" / "circe-core_2.13-0.14.0.jar",
        os.sub / "repo1.maven.org" / "maven2" / "org" / "typelevel" / "cats-kernel_2.13" / "2.9.0" / "cats-kernel_2.13-2.9.0.jar",
        os.sub / "repo1.maven.org" / "maven2" / "io" / "circe" / "circe-numbers_2.13" / "0.14.0" / "circe-numbers_2.13-0.14.0.jar"
      ).map(_.toString)
      val mapping = expectedModules.map(e => e -> readModules.find(m => m.endsWith(e))).toMap
      val missing = mapping.filter(_._2.isEmpty).map(_._1)
      val tooMuch = expectedModules.filter(m => !mapping.contains(m))
      assert(
        missing.isEmpty,
        tooMuch.isEmpty
//        readModules.size == expectedModules.size
      )
    }
  }
}
