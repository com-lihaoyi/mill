package mill.main.buildgen

import mill.main.buildgen.BuildGenUtil.{buildPackages, compactBuildTree, writeBuildObject}

import scala.collection.immutable.{SortedMap, SortedSet}

/*
TODO Can we just convert all generic type parameters to abstract type members?
 See https://stackoverflow.com/a/1154727/5082913.
 I think abstract type members are preferred in this case.
 */
trait BuildGenBase[M, D, I] {
  type C
  def convertWriteOut(cfg: C, shared: BuildGenUtil.BasicConfig, input: I): Unit = {
    val output = convert(input, cfg, shared)
    writeBuildObject(if (shared.merge.value) compactBuildTree(output) else output)
  }

  /**
   * A possibly optional module model which might be a parent directory of an actual module without its own sources.
   */
  type OM
  extension (om: OM) def toOption(): Option[M]

  def getModuleTree(input: I): Tree[Node[OM]]

  def convert(
      input: I,
      cfg: C,
      shared: BuildGenUtil.BasicConfig
  ): Tree[Node[BuildObject]] = {
    val moduleTree = getModuleTree(input)
    val moduleOptionTree = moduleTree.map(node => node.copy(value = node.value.toOption()))

    // for resolving moduleDeps
    val packages = buildPackages(
      moduleOptionTree.nodes().flatMap(node => node.value.map(m => node.copy(value = m)))
    )(getPackage)

    val baseInfo =
      shared.baseModule.fold(IrBaseInfo()) { getBaseInfo(input, cfg, _, packages.size) }

    moduleOptionTree.map(optionalBuild =>
      optionalBuild.copy(value =
        optionalBuild.value.fold(
          BuildObject(SortedSet("mill._"), SortedMap.empty, Seq("RootModule", "Module"), "", "")
        )(moduleModel => {
          val name = getArtifactId(moduleModel)
          println(s"converting module $name")

          val build = optionalBuild.copy(value = moduleModel)
          val inner = extractIrBuild(cfg, build, packages)

          val isNested = optionalBuild.dirs.nonEmpty
          BuildObject(
            imports =
              BuildGenUtil.renderImports(shared.baseModule, isNested, packages.size, extraImports),
            companions =
              shared.depsObject.fold(SortedMap.empty[String, BuildObject.Constants])(name =>
                SortedMap((name, SortedMap(inner.scopedDeps.namedIvyDeps.toSeq*)))
              ),
            supertypes = getSupertypes(cfg, baseInfo, build),
            inner = BuildGenUtil.renderIrBuild(inner, baseInfo),
            outer =
              if (isNested || baseInfo.moduleTypedef == null) ""
              else BuildGenUtil.renderIrTrait(baseInfo.moduleTypedef)
          )
        })
      )
    )
  }

  def extraImports: Seq[String]

  def getSupertypes(cfg: C, baseInfo: IrBaseInfo, build: Node[M]): Seq[String]

  def getBaseInfo(
      input: I,
      cfg: C,
      baseModule: String,
      packagesSize: Int
  ): IrBaseInfo

  def getPackage(moduleModel: M): (String, String, String)

  def getArtifactId(moduleModel: M): String

  def extractIrBuild(
      cfg: C,
      // baseInfo: IrBaseInfo, // `baseInfo` is no longer needed as we compare the `IrBuild` with `IrBaseInfo`/`IrTrait` in common code now.
      build: Node[M],
      packages: Map[(String, String, String), String]
  ): IrBuild
}

object BuildGenBase {
  trait MavenAndGradle[M, D] extends BuildGenBase[M, D, Tree[Node[M]]] {
    override def getModuleTree(input: Tree[Node[M]]): Tree[Node[M]] = input
    override type OM = M

    override def extraImports: Seq[String] = Seq.empty
  }
}
