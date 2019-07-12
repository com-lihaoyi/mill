package mill.api

import sbt.testing._

trait TestReporter {
  def logStart(event: Event): Unit

  def logFinish(event: Event): Unit


}

object  DummyReporter extends TestReporter {
  override def logStart(event:  Event): Unit = {

  }

  override def logFinish(event:  Event): Unit = {

  }
}
