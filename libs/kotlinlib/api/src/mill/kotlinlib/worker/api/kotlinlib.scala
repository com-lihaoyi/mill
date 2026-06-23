package mill.kotlinlib.worker

package object api {
  private[kotlinlib] inline def renderIntAsHex(sig: Int): String =
    String.format("%08x", sig: Integer)
}
