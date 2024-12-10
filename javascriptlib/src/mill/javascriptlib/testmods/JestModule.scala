package mill.javascriptlib.testmods

import mill.*
import os.*

trait JestModule extends TestModule {
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
  
  override def allSources: T[IndexedSeq[PathRef]] = Task {
    val tc = testConfigSource() match {
      case Some(s) => IndexedSeq(s.path)
      case None => IndexedSeq()
    }

    super.allSources() ++ (os.walk(testSource().path) ++ tc).filter(_.ext == "ts").map(PathRef(_))
  }

  override def testConfigSource: T[Option[PathRef]] =
    Task(Some(PathRef(millSourcePath / os.up / "jest.config.ts")))

  // specify config file path: --config /path/to/jest/config
  def getConfigFile: T[String] =
    Task { (compile()._1.path / "jest.config.ts").toString }

  override def compilerOptions: T[Map[String, ujson.Value]] =
    Task { super.compilerOptions() + ("resolveJsonModule" -> ujson.Bool(true)) }

  private def copyJestConfig: Task[Unit] = Task.Anon {
    testConfigSource() match {
      case Some(x) =>
        os.copy.over(
          x.path,
          compile()._1.path / "jest.config.ts"
        )
      case None => ()
    }

  }

  def test: T[CommandResult] = Task {
    copyJestConfig()
    os.call(
      (
        "node",
        npmInstall().path / "node_modules/jest/bin/jest.js",
        "--config",
        getConfigFile(),
        getPathToTest()
      ),
      stdout = os.Inherit,
      env = mkENV(),
      cwd = compile()._1.path
    )
  }
}
