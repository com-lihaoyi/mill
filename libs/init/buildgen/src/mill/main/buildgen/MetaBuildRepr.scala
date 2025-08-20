package mill.main.buildgen

import mainargs.ParserForClass

@mainargs.main
case class MetaBuildArgs(
    @mainargs.arg(short = 'm')
    packageName: String = "millbuild",
    @mainargs.arg(short = 'b')
    baseTraitSuffix: String = "BaseModule",
    @mainargs.arg(short = 'p')
    publishTraitSuffix: String = "PublishModule",
    @mainargs.arg(short = 'd')
    depsObjectName: String = "Deps",
    @mainargs.arg(short = 'M')
    noMetaBuild: mainargs.Flag
)
object MetaBuildArgs {
  given ParserForClass[MetaBuildArgs] = ParserForClass[MetaBuildArgs]
}

case class MetaBuildRepr(packageName: String, baseTraits: Seq[BaseTrait], depsObject: DepsObject)
