package mill.api

import sbt.testing.Event

/**
  * Bsp Context with functionality for retrieving compile
  * arguments provided by a Bsp client, as well as for logging
  * the start and finish of a task triggered by the request of
  * a Bsp client. Can be integrated into mill's Ctx to inject
  * Bsp functionality into tasks like compile/run/test.
  */
trait BspContext extends BspCompileArguments with TestReporter

/**
  * Dummy Bsp Context that does nothing
  * upon starting or finishing a task, and
  * contains no client-specified compilation
  * arguments
  */
object DummyBspContext extends BspContext {
  override def args = Seq.empty[String]

  override def logStart(event:  Event): Unit = {

  }

  override def logFinish(event:  Event): Unit = {

  }
}