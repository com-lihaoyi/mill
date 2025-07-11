package mill.scalalib

import sbt.testing._

class DoneMessageSuccessFramework extends Framework {
  def fingerprints() = Array.empty
  def name() = "DoneMessageSuccessFramework"
  def runner(
      args: Array[String],
      remoteArgs: Array[String],
      testClassLoader: ClassLoader
  ): Runner = new Runner {
    def args() = Array.empty
    def done() = "test success done message"
    def remoteArgs() = Array.empty
    def tasks(taskDefs: Array[TaskDef]) = Array.empty
  }
}
