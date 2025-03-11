package mill.main.buildgen

import mill.main.buildgen.BuildGenUtil.{buildPackages, compactBuildTree, writeBuildObject}

import scala.collection.immutable.SortedMap

trait BuildGenBase[M, D] {
  type C
  def convertWriteOut(cfg: C, shared: BuildGenUtil.Config, input: Tree[Node[M]]): Unit = {
    val output = convert(input, cfg, shared)
    writeBuildObject(if (shared.merge.value) compactBuildTree(output) else output)
  }

  def convert(
      input: Tree[Node[M]],
      cfg: C,
      shared: BuildGenUtil.Config
  ): Tree[Node[BuildObject]] = {
    // for resolving moduleDeps

    val packages = buildPackages(input)(getPackage)

    val baseInfo =
      shared.baseModule.fold(IrBaseInfo()) { getBaseInfo(input, cfg, _, packages.size) }

    input.map { build =>
      val name = getArtifactId(build.value)
      println(s"converting module $name")

      val inner = extractIrBuild(cfg, baseInfo, build, packages)

      val isNested = build.dirs.nonEmpty
      build.copy(value =
        BuildObject(
          imports = BuildGenUtil.renderImports(shared.baseModule, isNested, packages.size),
          companions =
            shared.depsObject.fold(SortedMap.empty[String, BuildObject.Constants])(name =>
              SortedMap((name, SortedMap(inner.scopedDeps.namedIvyDeps.toSeq*)))
            ),
          supertypes = getSuperTypes(cfg, baseInfo, build),
          inner = BuildGenUtil.renderIrBuild(inner),
          outer =
            if (isNested || baseInfo.moduleTypedef == null) ""
            else BuildGenUtil.renderIrTrait(baseInfo.moduleTypedef)
        )
      )
    }
  }

  def getSuperTypes(cfg: C, baseInfo: IrBaseInfo, build: Node[M]): Seq[String]

  def getBaseInfo(
      input: Tree[Node[M]],
      cfg: C,
      baseModule: String,
      packagesSize: Int
  ): IrBaseInfo

  def getPackage(model: M): (String, String, String)

  def getArtifactId(model: M): String

  def extractIrBuild(
      cfg: C,
      baseInfo: IrBaseInfo,
      build: Node[M],
      packages: Map[(String, String, String), String]
  ): IrBuild
}
