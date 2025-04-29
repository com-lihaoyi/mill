package mill.main
import java.io.File
import java.net.{ServerSocket, BindException}
import java.io.FileInputStream;
import mill.define.{Discover, ExternalModule}
import mill.*

object PortManager extends ExternalModule {
  var portsByName: Map[String, Set[Int]] = Map.empty[String, Set[Int]]

  def getPorts(tartgetNumberOfPorts: Int): Set[Int] = {
    var i = 0
    var ports = Set.empty[Int]

    this.synchronized {
      for (z <- 1 to 100) {

        if (i >= tartgetNumberOfPorts) {
          return ports
        }
        val socket = new ServerSocket(0)
        try {
          val port = socket.getLocalPort
          if (!ports.contains(port)) {
            ports = ports + port
            i += 1
          }

        } finally {
          socket.close()
        }
      }
    }
    ports
  }

  lazy val millDiscover = Discover[this.type]

}
