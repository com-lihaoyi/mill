package mill.api

import mill.api.daemon.experimental

import java.io.{ByteArrayOutputStream, PrintStream}

@experimental
object Debug {
  def apply[T](x: sourcecode.Text[T],
               tag: String = "",
               width: Int = 100,
               height: Int = 500,
               indent: Int = 2,
               escapeUnicode: Boolean = true,
               showFieldNames: Boolean = true)
              (implicit line: sourcecode.Line,
               fileName: sourcecode.FileName): T = {

    val baos = new ByteArrayOutputStream()
    pprint.logTo(x, tag, width, height, indent, escapeUnicode, showFieldNames, new PrintStream(baos))
    mill.constants.DebugLog.println(baos.toString)
    x.value
  }
}
