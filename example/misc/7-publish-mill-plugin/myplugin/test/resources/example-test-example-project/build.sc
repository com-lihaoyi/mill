import mill._, myplugin._

object foo extends RootModule with LineCountJavaModule


/** Usage

> ./mill run
Line Count: 17
...

*/