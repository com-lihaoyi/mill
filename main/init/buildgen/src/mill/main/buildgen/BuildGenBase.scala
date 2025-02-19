package mill.main.buildgen

import mill.main.buildgen.BuildGenUtil.{buildPackages, compactBuildTree, writeBuildObject}

import scala.collection.immutable.SortedMap

trait BuildGenBase[M, D, I] {
  type C
  def convertWriteOut(cfg: C, shared: BuildGenUtil.BasicConfig, input: I): Unit = {
    val output = convert(input, cfg, shared)
    writeBuildObject(if (shared.merge.value) compactBuildTree(output) else output)
  }

  def getProjectTree(input: I): Tree[Node[M]]

  def convert(
      input: I,
      cfg: C,
      shared: BuildGenUtil.BasicConfig
  ): Tree[Node[BuildObject]] = {
    val projectTree = getProjectTree(input)

    // for resolving moduleDeps
    val packages = buildPackages(projectTree)(getPackage)

    val baseInfo =
      shared.baseModule.fold(IrBaseInfo()) { getBaseInfo(input, cfg, _, packages.size) }

    projectTree.map { build =>
      val name = getArtifactId(build.value)
      println(s"converting module $name")

      val inner = extractIrBuild(cfg, baseInfo, build, packages)

      val isNested = build.dirs.nonEmpty
      build.copy(value =
        BuildObject(
          imports = BuildGenUtil.renderImports(shared.baseModule, isNested, packages.size, extraImports),
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

  def extraImports: Seq[String]

  def getSuperTypes(cfg: C, baseInfo: IrBaseInfo, build: Node[M]): Seq[String]

  def getBaseInfo(
      input: I,
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

object BuildGenBase {
  trait BaseInfoFromSubproject[M, D] extends BuildGenBase[M, D, Tree[Node[M]]] {
    override def getProjectTree(input: Tree[Node[M]]): Tree[Node[M]] = input
  }
}
