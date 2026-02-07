package mill.api.daemon;

/**
 * This exception is specifically handled in the Mill launcher and runner.
 * Use it when you need to exit Mill with a nice error message without showing a stack trace.
 *
 * Even though it lives in the mill-constants maven artifact,
 * it is in the mill.api.daemon JVM package for backwards compatibility,
 */
public class MillException extends RuntimeException {
  public MillException(String msg) {
    super(msg);
  }
}
