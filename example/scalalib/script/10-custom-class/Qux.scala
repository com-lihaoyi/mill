//| extends: millbuild.LineCountScalaModule
//| scalaVersion: 3.8.1
package qux

def getLineCount() = {
  scala.io.Source
    .fromResource("line-count.txt")
    .mkString
}

def main() = {
  println(s"Line Count: ${getLineCount()}")
}
