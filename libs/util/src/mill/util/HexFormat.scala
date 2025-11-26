package mill.util

object HexFormat {
  def bytesToHex(bytes: IterableOnce[Byte]): String = bytes.map(String.format("%02x", _)).mkString
}
