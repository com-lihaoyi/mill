package mill.javalib.worker

import mill.api.MappedRoots
import mill.rpc.MillRpcWireTransport

import java.io.{BufferedReader, PrintStream}

class NoMappedRootsMillRcpWireTransport(
    name: String,
    serverToClient: BufferedReader,
    clientToServer: PrintStream,
    writeSynchronizer: AnyRef
) extends MillRpcWireTransport(
      name = name,
      serverToClient = serverToClient,
      clientToServer = clientToServer,
      writeSynchronizer = writeSynchronizer
    ) {
  override def writeSerialized[A: upickle.Writer](message: A, log: String => Unit): Unit = {
    // RPC communication is local and uncached, so we don't want to use any root mapping
    MappedRoots.withMapping(Seq()) {
      super.writeSerialized(message, log)
    }
  }
}
