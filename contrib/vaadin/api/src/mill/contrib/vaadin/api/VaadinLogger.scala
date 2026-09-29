package mill.contrib.vaadin.api

/** Receives the log output of Vaadin's build tooling. */
trait VaadinLogger {
  def error(msg: String): Unit
  def warn(msg: String): Unit
  def info(msg: String): Unit
  def debug(msg: String): Unit
}
