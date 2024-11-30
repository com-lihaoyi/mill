package mill.javascriptlib
import mill.*
import os.*
import mill.define.Target
import scala.collection.immutable.IndexedSeq

trait JestModule extends TypeScriptModule {
  override def npmDevDeps: T[Seq[String]] = Task {
    Seq(
      "@types/jest@^29.5.14",
      "@babel/core@^7.26.0",
      "@babel/preset-env@^7.26.0",
      "jest@^29.7.0",
      "ts-jest@^29.2.5",
      "babel-jest@^29.7.0"
    )
  }

  def testPath: Target[PathRef] = Task.Source(millSourcePath / "test")

  def jestConfig: Target[PathRef] = Task.Source(millSourcePath / os.up / "jest.config.ts")

  override def allSources: Target[IndexedSeq[PathRef]] = Task {
    (os.walk(sources().path) ++ os.walk(testPath().path) ++ IndexedSeq(jestConfig().path))
      .filter(_.ext == "ts")
      .map(PathRef(_))
  }

  override def mkENV = Task.Anon {
    val javascriptOut = compile()._1.path
    // env
    // note: ' npmInstall().path / "node_modules" ' required in NODE_PATH for jest to find preset: ts-jest
    Map("NODE_PATH" -> Seq(
      ".",
      javascriptOut,
      npmInstall().path,
      npmInstall().path / "node_modules"
    ).mkString(":"))
  }

  // specify config file path: --config /path/to/jest/config
  def jestConfigFile: Task[String] =
    Task { (compile()._1.path / "jest.config.js").toString() }

  // specify test dir path/to/test
  def jestPathToTest: Task[String] =
    Task { compile()._1.path.toString() }

  def test: Target[CommandResult] = Task {
    os.call(
      (
        "node",
        npmInstall().path / "node_modules/jest/bin/jest.js",
        "--config",
        jestConfigFile(),
        jestPathToTest()
      ),
      stdout = os.Inherit,
      env = mkENV()
    )
  }
}
