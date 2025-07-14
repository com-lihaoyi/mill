package mill.scalalib

import sbt.testing._

class DoneMessageNullFramework extends Framework {
  def fingerprints() = Array.empty
  def name() = "DoneMessageNullFramework"
  def runner(
      args: Array[String],
      remoteArgs: Array[String],
      testClassLoader: ClassLoader
  ): Runner = new Runner {
    def args() = Array.empty
    def done() = null
    def remoteArgs() = Array.empty
    def tasks(taskDefs: Array[TaskDef]) = Array.empty
  }
}
