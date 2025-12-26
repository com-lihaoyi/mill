//| moduleDeps: [bar/Bar.scala]
//| scalaVersion: 3.7.1

package foo

def main(args: Array[String]): Unit = println(bar.generateHtml(args(0)))
