package com.etsy.sbt.checkstyle

import os.Path

import scala.io.Source
import upickle.default._

/**
 * Represents a Checkstyle XML configuration located locally, on the class path or remotely at a URL
 *
 * @author Joseph Earl
 */
sealed trait CheckstyleConfigLocation {
  def read(resources: Seq[Path]): String
}

object CheckstyleConfigLocation {
  case class URL(url: String) extends CheckstyleConfigLocation {
    override def read(resources: Seq[Path]): String = Source.fromURL(url).mkString
  }

  case class File(path: String) extends CheckstyleConfigLocation {
    override def read(resources: Seq[Path]): String = Source.fromFile(path).mkString
  }

  object File {
    implicit val rw: ReadWriter[File] = readwriter[String].bimap[File](_.path, File(_))
  }

  case class Classpath(name: String) extends CheckstyleConfigLocation {
    override def read(resources: Seq[Path]): String = {
      val classpath = resources.map((f) => f.toIO.toURI.toURL)
      val loader = new java.net.URLClassLoader(classpath.toArray, getClass.getClassLoader)
      Source.fromInputStream(loader.getResourceAsStream(name)).mkString
    }
  }
}
