package mill.javascriptlib
import mill.*, javascriptlib.*
import os.*

trait JestModule extends TypeScriptModule {
  def testPath: Target[PathRef] = Task.Source(millSourcePath / "test")
  val testConfigPath = millSourcePath / os.up / "jest.config.ts"

  override def allSources: Target[IndexedSeq[PathRef]] = Task.Anon {
    (os.walk(sources().path) ++ os.walk(testPath().path) ++ IndexedSeq(testConfigPath))
      .filter(_.ext == "ts")
      .map(PathRef(_))
  }

  override def mkENV: Target[Map[String, String]] = Task {
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

  def test: Target[CommandResult] = Task {
    os.call(
      ("node", npmInstall().path / "node_modules/jest/bin/jest.js", compile()._1.path),
      stdout = os.Inherit,
      env = mkENV()
    )
  }
}
