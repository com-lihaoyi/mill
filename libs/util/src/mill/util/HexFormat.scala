package mill.util

object HexFormat {
  def bytesToHex(bytes: IterableOnce[Byte]): String = {
    val sb = new StringBuilder
    for (b <- bytes) sb.append(String.format("%02x", b))
    sb.toString
  }
}
