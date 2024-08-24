//// SNIPPET:BUILD
import mill._, javalib._

object foo extends RootModule with JavaModule {
  def ivyDeps = Agg(
    ivy"com.fasterxml.jackson.core:jackson-databind:2.13.4",
  )
}

//// SNIPPET:SCALAIVY

//// SNIPPET:USAGE
/** Usage

> ./mill run i am cow
JSONified using Jackson: ["i","am","cow"]

*/
