package mill.runner.api
abstract class ScalaPlatform(val number: Int)

object ScalaPlatform {
  case object JVM extends ScalaPlatform(1)
  case object JS extends ScalaPlatform(2)
  case object Native extends ScalaPlatform(3)
}
