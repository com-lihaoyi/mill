package mill.api
import os.Path
import upickle.default.ReadWriter as RW
import scala.reflect.ClassTag
import scala.util.matching.Regex
import mill.api.WorkspaceRoot

/**
 * Defines a trait which handles deerialization of paths, in a way that can be used by both path refs and paths
 */
trait PathUtils {
  /*
   * Returns a list of paths and their variables to be substituted with.
   */
  implicit def substitutions(): List[(String, String)] = {
    val workspaceRootPath: String = WorkspaceRoot.workspaceRoot.toString

    var result = List((workspaceRootPath, "*$WorkplaceRoot*"))

    val javaHome = System.getProperty("java.home");
    result = result :+ (javaHome, "*$JavaHome*")

    val courseierPath = coursier.paths.CoursierPaths.cacheDirectory().toString
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
    var result = a.toString
    subs.foreach { case (path, sub) =>
      // Serializes by replacing the path with the substitution
      result = result.replace(path, sub)
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
      val pathDepth = path.count(_ == '/')
      // In the case that a path is in the folder of another path, it picks the path with the most depth
      if (result.startsWith(sub) && pathDepth >= depth) {
        depth = pathDepth
        result = a.replace(sub, path)
      }
    }
    os.Path(result)
  }
}
