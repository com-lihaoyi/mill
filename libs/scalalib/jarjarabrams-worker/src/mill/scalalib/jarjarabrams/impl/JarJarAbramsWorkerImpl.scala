package mill.scalalib.jarjarabrams.impl

import com.eed3si9n.jarjarabrams.{ShadePattern, Shader}
import mill.define.PathRef
import mill.util.JarManifest
import os.Generator

import java.io.{ByteArrayInputStream, InputStream, SequenceInputStream}
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.StandardOpenOption
import java.util.Collections
import java.util.jar.JarFile
import java.util.regex.Pattern
import scala.jdk.CollectionConverters._
import scala.util.Using
import mill.scalalib.Assembly
import mill.scalalib.Assembly.*

object JarJarAbramsWorkerImpl  {

  def loadShadedClasspath(
                           inputPaths: Seq[os.Path],
                           assemblyRules: Seq[Assembly.Rule]
                         ): (Generator[(String, UnopenedInputStream)], ResourceCloser) = {
    val shadeRules = assemblyRules.collect {
      case Rule.Relocate(from, to) => ShadePattern.Rename(List(from -> to)).inAll
    }
    val shader =
      if (shadeRules.isEmpty)
        (name: String, inputStream: UnopenedInputStream) => Some(name -> inputStream)
      else {
        val shader = Shader.bytecodeShader(shadeRules, verbose = false, skipManifest = true)
        (name: String, inputStream: UnopenedInputStream) => {
          val is = inputStream()
          shader(is.readAllBytes(), name).map {
            case (bytes, name) =>
              name -> (() =>
                new ByteArrayInputStream(bytes) {
                  override def close(): Unit = is.close()
                }
                )
          }
        }
      }

    val pathsWithResources = inputPaths.filter(os.exists).map { path =>
      if (os.isFile(path)) path -> Some(new JarFile(path.toIO))
      else path -> None
    }

    val generators = Generator.from(pathsWithResources).flatMap {
      case (path, Some(jarFile)) =>
        Generator.from(jarFile.entries().asScala.filterNot(_.isDirectory))
          .flatMap(entry => shader(entry.getName, () => jarFile.getInputStream(entry)))
      case (path, None) =>
        os.walk
          .stream(path)
          .filter(os.isFile)
          .flatMap(subPath =>
            shader(subPath.relativeTo(path).toString, () => os.read.inputStream(subPath))
          )
    }

    (generators, () => pathsWithResources.flatMap(_._2).iterator.foreach(_.close()))
  }

}
