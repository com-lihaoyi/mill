package mill.bsp.worker.debug

import ch.epfl.scala.debugadapter._

abstract class AbstractDebuggee extends Debuggee {}

abstract class ScalaTestSuitesDebuggee(scalaVersion: String, debug: String => Unit) extends AbstractDebuggee {
  def javaRuntime: Option[JavaRuntime] = {
    debug("javaRuntime called")
    ???
  }
  def libraries: Seq[Library] = {
    debug("libraries called")
    ???
  }
  def modules: Seq[Module] = {
    debug("modules called") 
    ???
  }
  val name: String = s"test adapter ${scala.util.Random.nextInt()}"
  def observeClassUpdates(onClassUpdate: Seq[String] => Unit): java.io.Closeable = {
    debug("observeClassUpdates called")
    new java.io.Closeable {
      def close(): Unit = ()
    }
  }

  def scalaVersion: ScalaVersion = ScalaVersion(scalaVersion)
  def unmanagedEntries: Seq[UnmanagedEntry] = {
    debug("unmanagedEntries called")
    ???
  }
}
