package mill.api

import java.io.{InputStream, PrintStream}

class SystemStreams(val out: PrintStream, val err: PrintStream, val in: InputStream, val bspLog: Option[PrintStream] = None)
