package mill
package contrib

import ammonite.ops
import ammonite.ops.Path
import mill.define.Target
import mill.scalalib._
import mill.util.{Loose, TestEvaluator, TestUtil}
import utest.{Tests, TestSuite, _}
import utest.framework.TestPath

object ScalaJSWebpackTests extends TestSuite {

  object WebpackModule extends TestUtil.BaseModule with ScalaJSWebpackModule {
    override def scalaJSVersion: T[String] = "0.6.25"

    override def scalaVersion: T[String] = "2.12.6"

    override def ivyDeps: Target[Loose.Agg[Dep]] = Agg(
      ivy"io.github.outwatch::outwatch::1.0.0-RC2",
    )
  }

  def webpackTest[T](
      m: TestUtil.BaseModule)(
      f: TestEvaluator => T)(
      implicit
      tp: TestPath): T = {
    val ev = new TestEvaluator(m)
    ops.rm(ev.outPath)
    f(ev)
  }

  val resPath: Path =
    ops.pwd / "contrib" / "webpacklib" / "test" / "resources"
  val sProps: String =
    ops.read(resPath / "snabbdom-custom-props.js")
  val npmJson: String =
    ops.read(resPath / "package.json")
  val wpConf: String => String = p =>
    s"""module.exports = {
      |  "mode": "development",
      |  "devtool": "source-map",
      |  "entry": "out.js",
      |  "output": {
      |    "path": "$p",
      |    "filename": "out-bundle.js"
      |  }
      |};
      |""".stripMargin

  override def tests: Tests = Tests {
    "bundlerDeps" - {
      "extractFromJars" - webpackTest(WebpackModule) { ev =>
        val Right((result, _)) = ev(WebpackModule.sbtBundlerDeps)

        assert(
          result.compileDependencies == Seq("snabbdom" -> "0.7.1"),
          result.compileDevDependencies == Nil,
          result.jsSources get "snabbdom-custom-props.js" contains sProps,
        )
      }
    }

    "webpack" - {
      "writeJsSources" - webpackTest(WebpackModule) { ev =>
        val Right((result, _)) = ev(WebpackModule.writeBundleSources)

        result(
          WebpackModule.JsDeps(
            Nil,
            Nil,
            Map("snabbdom-custom-props.js" -> sProps)),
          ev.outPath)

        val src = ops.read(ev.outPath / "snabbdom-custom-props.js")

        assert(
          src == sProps,
          )
      }

      "writeCfg" - webpackTest(WebpackModule) { ev =>
        val Right((result, _)) = ev(WebpackModule.writeWpConfig)

        result(
          ev.outPath,
          "webpack.config.js",
          "out.js",
          "out-bundle.js",
          false)

        val cfg = ops.read(ev.outPath / "webpack.config.js")

        assert(
          cfg == wpConf(ev.outPath.toString),
          )
      }

      "writePackageJson" - webpackTest(WebpackModule) { ev =>
        val Right((result, count)) = ev(WebpackModule.writePackageSpec)

        result(
          WebpackModule.JsDeps(
            List("snabbdom" -> "0.7.1"),
            Nil,
            Map.empty),
          ev.outPath)

        val pkg = ops.read(ev.outPath / "package.json")

        assert(
          pkg == npmJson,
          count > 0)
      }
    }
  }
}
