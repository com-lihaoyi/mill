import $ivy.`com.lihaoyi::myplugin:0.0.1`
import mill._, myplugin._

object foo extends RootModule with LineCountJavaModule{
  def lineCountResourceFileName = "line-count.txt"
}

