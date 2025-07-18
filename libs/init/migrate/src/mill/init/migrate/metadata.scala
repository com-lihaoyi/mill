package mill.init.migrate

import mill.init.migrate.BuildDefaults.*

case class BaseTrait(moduleName: String, supertypes: Seq[String], data: ModuleData):

  def extendTypes(supertypes: IterableOnce[String]) =
    copy(supertypes = this.supertypes ++ supertypes)

object BaseTrait:

  def apply(name: String, data: ModuleData): BaseTrait =
    apply(typeName(name, "Module"), moduleSupertypes(data), data)

case class ModuleMetadata(
    baseTrait: Option[BaseTrait] = None,
    crossVersions: Seq[String] = Nil,
    nestedTrait: Option[BaseTrait] = None
)
