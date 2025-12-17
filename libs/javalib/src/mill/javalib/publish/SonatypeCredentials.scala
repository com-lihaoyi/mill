package mill.javalib.publish

final case class SonatypeCredentials(
    username: String,
    password: String
) {
  override def toString: String = "SonatypeCredentials(username: <redacted>, password: <redacted>)"
}
