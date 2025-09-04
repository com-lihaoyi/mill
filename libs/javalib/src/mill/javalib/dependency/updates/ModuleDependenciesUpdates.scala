package mill.javalib.dependency.updates

final case class ModuleDependenciesUpdates(
    modulePath: String,
    dependencies: Seq[DependencyUpdates]
)

object ModuleDependenciesUpdates {
  implicit val rw: upickle.ReadWriter[ModuleDependenciesUpdates] =
    upickle.macroRW
}
