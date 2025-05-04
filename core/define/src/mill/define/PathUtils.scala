package mill.define
import scala.reflect.ClassTag
import java.io.File;

/**
 * Defines a trait which handles deerialization of paths, in a way that can be used by both path refs and paths
 */
trait PathUtils {
  /*
   * Returns a list of paths and their variables to be substituted with.
   */
  implicit def substitutions(): List[(os.Path, String)] = {
    val javaHome = os.Path(System.getProperty("java.home"))
    var result = List((javaHome, "*$JavaHome*"))
    val courseierPath = os.Path(coursier.paths.CoursierPaths.cacheDirectory().getAbsolutePath())
    result = result :+ (courseierPath, "*$CourseirCache*")
    result
  }

  /*
   * Handles the JSON serialization of paths. Normalizes paths based on variables returned by PathUtils.substitutions.
   * Substituting specific paths with variables as they are read from JSON.
   * The inverse function is PathUtils.deserializeEnvVariables.
   */
  implicit def serializeEnvVariables(a: os.Path): String = {
    val subs = substitutions()
    val stringified = a.toString
    var result = a.toString
    var depth = 0
    subs.foreach { case (path, sub) =>
      // Serializes by replacing the path with the substitution
      val pathDepth = path.segments.length
      val pathString = path.toString
      result = stringified.replace(pathString, sub)
    }
    result
  }

  /*
   * Handles the JSON deserialization of paths. Normalizes paths based on variables returned by PathUtils.substitutions.
   * Substituting specific strings with variables as they are read from JSON.
   * The inverse function is PathUtils.serializeEnvVariables
   */
  implicit def deserializeEnvVariables(a: String): os.Path = {
    val subs = substitutions()
    var result = a
    var depth = 0
    subs.foreach { case (path, sub) =>
      val pathDepth = path.segments.length
      // In the case that a path is in the folder of another path, it picks the path with the most depth
      result = a.replace(sub, path.toString)
    }
    os.Path(result)
  }
}

object PathUtils extends PathUtils {
  def getSubstitutions(): List[(os.Path, String)] = {
    substitutions()
  }
}
