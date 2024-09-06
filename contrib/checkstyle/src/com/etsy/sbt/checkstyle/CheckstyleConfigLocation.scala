package com.etsy.sbt.checkstyle

import os.Path

import scala.io.Source

/**
 * Represents a Checkstyle XML configuration located locally, on the class path or remotely at a URL
 *
 * @author Joseph Earl
 */
sealed abstract class CheckstyleConfigLocation(val location: String) {
  def read(resources: Seq[Path]): String
}

object CheckstyleConfigLocation {
  case class URL(url: String) extends CheckstyleConfigLocation(url) {
    override def read(resources: Seq[Path]): String = Source.fromURL(url).mkString
  }

  case class File(path: String) extends CheckstyleConfigLocation(path) {
    override def read(resources: Seq[Path]): String = Source.fromFile(path).mkString
  }

  case class Classpath(name: String) extends CheckstyleConfigLocation(name) {
    override def read(resources: Seq[Path]): String = {
      val classpath = resources.map((f) => f.toIO.toURI.toURL)
      val loader = new java.net.URLClassLoader(classpath.toArray, getClass.getClassLoader)
      Source.fromInputStream(loader.getResourceAsStream(name)).mkString
    }
  }
}
