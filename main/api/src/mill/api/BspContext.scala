package mill.api

import sbt.testing.Event

trait BspContext extends BspCompileArguments with TestReporter

object DummyBspContext extends BspContext {
  override def args = Seq.empty[String]
  override def logStart(event:  Event): Unit = {

  }

  override def logFinish(event:  Event): Unit = {

  }
}