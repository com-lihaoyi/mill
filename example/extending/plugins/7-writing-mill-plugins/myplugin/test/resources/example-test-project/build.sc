import $ivy.`com.lihaoyi::myplugin:0.0.1`
import mill._, myplugin._

object foo extends RootModule with LineCountJavaModule{
  def lineCountResourceFileName = "line-count.txt"
}

/** Usage

> ./mill run
Line Count: 17
...

> printf "\n" >> src/foo/Foo.java

> ./mill run
Line Count: 18
...

*/