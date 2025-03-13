package mill.main.buildgen

case class MetaModule(
    name: String,
    supertypes: Seq[String],
    crossVersions: Seq[String] = Nil,
    moduleConfigs: Seq[IrModuleConfig] = Nil,
    nestedModules: Seq[MetaModule] = Nil
)

case class MetaPackage(
    baseDir: os.SubPath,
    imports: Seq[String],
    module: MetaModule
)
