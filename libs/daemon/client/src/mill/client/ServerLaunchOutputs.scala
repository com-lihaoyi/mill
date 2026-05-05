package mill.client

case class ServerLaunchOutputs(
    /** The stdout of the server (if any). */
    stdout: Option[String],
    /** The stderr of the server (if any). */
    stderr: Option[String]
) {
  def debugString: String = {
    val sb = new StringBuilder
    stdout match {
      case Some(s) => sb.append("Daemon stdout:\n\n").append(s).append("\n\n")
      case None => sb.append("No daemon stdout\n\n")
    }
    stderr match {
      case Some(s) => sb.append("Daemon stderr:\n\n").append(s).append("\n\n")
      case None => sb.append("No daemon stderr\n\n")
    }
    sb.toString
  }

  override def toString: String = s"ServerLaunchOutputs{${debugString}}"
}
