package mill

/**
 * Experimental toolchain for building Python apps using Mill's
 * [[PythonModule]]. Supports publishing via [[pythonlib.PublishModule]],
 * linting via [[pythonlib.RuffModule]], and code coverage via
 *  [[pythonlib.CoverageModule]],
 */
package object pythonlib {

  // These types are commonly used in python modules. Export them to make using
  // them possible without an import.
  type License = mill.scalalib.publish.License
  val License = mill.scalalib.publish.License
  type PublishMeta = PublishModule.PublishMeta
  val PublishMeta = PublishModule.PublishMeta
  type Developer = PublishModule.Developer
  val Developer = PublishModule.Developer

}
