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
    if (parts.sizeIs == 1) this
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
  def fromString(str: String): Either[String, MillRpcRequestId] = {
    val parts = str.split(":").iterator.map(Part.unapply).toVector
    if (parts.contains(None)) Left(s"invalid request id: \"$str\" ($parts)")
    else if (parts.isEmpty) Left(s"empty request id: \"$str\"")
    else Right(MillRpcRequestId(parts.flatten))
  }

  given rw: upickle.ReadWriter[MillRpcRequestId] =
    upickle.readwriter[String].bimap(
      _.toString,
      fromString(_).fold(err => throw IllegalArgumentException(err), identity)
    )

  def initialForClient: MillRpcRequestId = apply(Vector(Part(Kind.Client, -1)))

  /** Creates an instance, throws if the vector is empty. */
  def unsafe(parts: Vector[MillRpcRequestId.Part]): MillRpcRequestId = apply(parts)

  enum Kind {
    case Client, Server

    def asChar: Char = this match {
      case Client => 'c'
      case Server => 's'
    }
  }
  object Kind {
    def unapply(c: Char): Option[Kind] = c match {
      case 'c' => Some(Kind.Client)
      case 's' => Some(Kind.Server)
      case _ => None
    }
  }

  case class Part(kind: Kind, id: Long) {
    override def toString: String = s"${kind.asChar}$id"
  }
  object Part {
    def unapply(str: String): Option[Part] = {
      if (str.length < 2) None
      else for {
        kind <- str.headOption.flatMap(Kind.unapply)
        id <- str.tail.toLongOption
      } yield apply(kind, id)
    }
  }
}
