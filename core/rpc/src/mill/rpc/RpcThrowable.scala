package mill.rpc

import scala.util.control.NoStackTrace

/** Serialized [[Throwable]]. */
case class RpcThrowable(
    message: String,
    stacktrace: Vector[RpcStackTraceElement],
    cause: Option[RpcThrowable]
) derives upickle.default.ReadWriter {
  def toThrowable: Throwable = {
    val t = new Throwable(message, cause.map(_.toThrowable).orNull) with NoStackTrace
    t.setStackTrace(stacktrace.iterator.map(_.toStackTraceElement).toArray)
    t
  }
}
object RpcThrowable {
  def apply(t: Throwable): RpcThrowable = apply(
    t.getMessage,
    t.getStackTrace.iterator.map(RpcStackTraceElement.apply).toVector,
    Option(t.getCause).map(apply)
  )
}

case class RpcStackTraceElement(
    classLoaderName: String,
    moduleName: String,
    moduleVersion: String,
    declaringClass: String,
    methodName: String,
    fileName: String,
    lineNumber: Int
) derives upickle.default.ReadWriter {
  def toStackTraceElement: StackTraceElement =
    StackTraceElement(
      classLoaderName,
      moduleName,
      moduleVersion,
      declaringClass,
      methodName,
      fileName,
      lineNumber
    )
}
object RpcStackTraceElement {
  def apply(e: StackTraceElement): RpcStackTraceElement = apply(
    classLoaderName = e.getClassLoaderName,
    moduleName = e.getModuleName,
    moduleVersion = e.getModuleVersion,
    declaringClass = e.getClassName,
    methodName = e.getMethodName,
    fileName = e.getFileName,
    lineNumber = e.getLineNumber
  )
}
