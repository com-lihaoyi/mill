import mill.Agg
import mill.scalalib._

object core extends ScalaModule{
  def scalaVersion = "2.12.8"
  def ivyDeps = Agg(
    ivy"org.eclipse.jetty:jetty-websocket:8.1.16.v20140903",
    ivy"org.eclipse.jetty:jetty-server:8.1.16.v20140903"
  )
}

def thingy = T{ Seq("hello", "world") }
