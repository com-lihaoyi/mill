import scala.reflect.internal.annotations.compileTimeOnly
package object acyclic {
  /**
   * Import this within a file to make Acyclic verify that the file does not
   * have any circular dependencies with other files.
   */
  @compileTimeOnly("acyclic.file is just a marker and not a real value")
  def file = ()

  /**
   *
   */
  @compileTimeOnly("acyclic.file is just a marker and not a real value")
  def skipped = ()

  /**
   * Import this within a package object to make Acyclic verify that the entire
   * package does not have any circular dependencies with other files or
   * packages. Circular dependencies *within* the package are Ok.
   */
  @compileTimeOnly("acyclic.pkg is just a marker and not a real value")
  def pkg = ()
}
