package mill.rpc

/**
 * Sequential request ID that reflects the back-and-forth request flow between the server and the client.
 *
 * For example:
 * {{{
 * // Client initiates a request
 * c0
 * // Server asks client for more data
 * c0:s0
 * // Client responds, server asks for even more data
 * c0:s1
 * // Client responds, server asks for even more data
 * c0:s2
 * // Client sends a request to server
 * c0:s2:c0
 * // Everyone responds, client sends a new request
 * c1
 * }}}
 */
case class MillRpcRequestId private (parts: Vector[MillRpcRequestId.Part]) {
  assert(parts.nonEmpty, "must have at least one part")

  def requestStartedFromClient: MillRpcRequestId = requestStartedFrom(MillRpcRequestId.Kind.Client)
  def requestStartedFromServer: MillRpcRequestId = requestStartedFrom(MillRpcRequestId.Kind.Server)

  override def toString: String = parts.mkString(":")

  def requestFinished: MillRpcRequestId =
    if (parts.sizeIs == 1) requestStartedFrom(parts.head.kind)
    else copy(parts.init)

  def requestStartedFrom(kind: MillRpcRequestId.Kind): MillRpcRequestId = {
    val lastPart = parts.last
    lastPart.kind match {
      case `kind` => copy(parts.init :+ lastPart.copy(id = lastPart.id + 1))
      case _ => copy(parts :+ MillRpcRequestId.Part(kind, 0L))
    }
  }
}
object MillRpcRequestId {
  enum Kind {
    case Client, Server

    def asChar: Char = this match {
      case Client => 'c'
      case Server => 's'
    }
  }
  case class Part(kind: Kind, id: Long) {
    override def toString: String = s"${kind.asChar}$id"
  }

  def initialForClient: MillRpcRequestId = apply(Vector(Part(Kind.Client, -1)))
}
