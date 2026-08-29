//| moduleDeps: [//bar/Bar.scala]
//| scalaVersion: 3.9.0

package foo

def main(args: Array[String]): Unit = println(bar.generateHtml(args(0)))
