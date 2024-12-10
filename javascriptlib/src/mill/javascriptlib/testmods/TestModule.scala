package mill.javascriptlib.testmods

import mill.*
import mill.javascriptlib.TypeScriptModule
import os.*

trait TestModule extends TypeScriptModule {
  def testSource: T[PathRef] = Task.Source(millSourcePath / "test")

  // test configuration file.
  def testConfigSource: T[Option[PathRef]] = Task(None)

  override def allSources: T[IndexedSeq[PathRef]] = Task {
    (os.walk(sources().path) ++ os.walk(testSource().path))
      .filter(file => file.ext == "ts")
      .map(PathRef(_))
  }

  override def mkENV: T[Map[String, String]] = Task.Anon {
    val javascriptOut = compile()._1.path
    Map("NODE_PATH" -> Seq(
      ".",
      javascriptOut,
      npmInstall().path,
      npmInstall().path / "node_modules"
    ).mkString(":"))
  }

  // specify test dir path/to/test
  def getPathToTest: T[String] = Task { compile()._2.path.toString }

  def test: T[CommandResult]
}
