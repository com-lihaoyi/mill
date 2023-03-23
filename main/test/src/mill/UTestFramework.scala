package mill
import utest.framework.{Result, StackMarker}
import utest.ufansi.Str

class UTestFramework extends utest.runner.Framework {
  override def exceptionStackFrameHighlighter(s: StackTraceElement) = {
    s.getClassName.startsWith("mill.")
  }
  override def setup() = {

    os.remove.all(os.pwd / "target" / "workspace")
  }

  override def formatException(x: Throwable, leftIndent: String) = {
    val output = collection.mutable.Buffer.empty[String]
    var current = x
    while (current != null) {

      output.append(
          lineWrapInput(
            current.getMessage match {
              case null => ""
              case nonNull => ": " + nonNull
            },
            leftIndent
          ).mkString(leftIndent)
      )

      val stack = current.getStackTrace


      StackMarker.filterCallStack(stack)
        .foreach { e =>
          // Scala.js for some reason likes putting in full-paths into the
          // filename slot, rather than just the last segment of the file-path
          // like Scala-JVM does. This results in that portion of the
          // stacktrace being terribly long, wrapping around and generally
          // being impossible to read. We thus manually drop the earlier
          // portion of the file path and keep only the last segment

          val filenameFrag = e.getFileName match {
            case null => "Unknown"
            case fileName =>
              val shortenedFilename = fileName.lastIndexOf('/') match {
                case -1 => fileName
                case n => fileName.drop(n + 1)
              }

              exceptionLineNumberColor(shortenedFilename) +
              ":" +
              exceptionLineNumberColor(e.getLineNumber.toString)

          }

          val frameIndent = leftIndent + "  "


          output.append(
            "\n", frameIndent,

            lineWrapInput(
              Seq(
                e.getClassName + ".",
                e.getMethodName,
                "(",
                filenameFrag,
                ")"
              ).mkString,
              frameIndent
            ).mkString(frameIndent)

          )
        }
      current = current.getCause
      if (current != null) output.append("\n", leftIndent)
    }

    utest.ufansi.Str(output.mkString, errorMode = utest.ufansi.ErrorMode.Sanitize)
  }

}
