package mill.rpc

/**
 * Thrown when an RPC operation fails because the transport (socket/connection)
 * has been closed, typically because the client disconnected.
 *
 * This is distinct from [[InterruptedException]] which indicates thread interruption.
 */
class RpcTransportClosedException()
    extends Exception(s"Transport closed while waiting for response to request.")
