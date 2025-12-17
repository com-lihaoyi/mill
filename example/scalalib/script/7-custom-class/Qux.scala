//| extends: [millbuild.LineCountScalaModule]
//| scalaVersion: 3.7.3
package qux

def getLineCount() = {
  scala.io.Source
    .fromResource("line-count.txt")
    .mkString
}

@main def main() = {
  println(s"Line Count: ${getLineCount()}")
}
