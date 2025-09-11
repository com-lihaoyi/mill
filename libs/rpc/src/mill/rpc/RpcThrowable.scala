package mill.rpc

import scala.util.control.NoStackTrace

/** Serialized [[Throwable]]. */
case class RpcThrowable(
    className: String,
    message: Option[String],
    stacktrace: Vector[RpcStackTraceElement],
    cause: Option[RpcThrowable]
) extends RuntimeException(message.orNull, cause.orNull) with NoStackTrace
    derives upickle.ReadWriter {
  setStackTrace(stacktrace.iterator.map(_.toStackTraceElement).toArray)

  override def toString: String = {
    val self = getClass.getCanonicalName
    message match {
      case Some(message) => s"$self($className: $message)"
      case None => s"$self($className)"
    }
  }
}
object RpcThrowable {
  def apply(t: Throwable): RpcThrowable = apply(
    t.getClass.getCanonicalName,
    Option(t.getMessage),
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
) derives upickle.ReadWriter {
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
