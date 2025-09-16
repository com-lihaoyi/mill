package mill.main.buildgen

import mill.main.buildgen.BuildGenUtil.{compactBuildTree, writeBuildObject}

import scala.collection.immutable.{SortedMap, SortedSet}

trait BuildGenBase {
  type M
  type D
  type I
  type C
  def convertWriteOut(cfg: C, shared: BuildGenUtil.BasicConfig, input: I): Unit = {
    val output = convert(input, cfg, shared)
    writeBuildObject(
      if (shared.merge.value) compactBuildTree(output) else output,
      shared.jvmId
    )
  }

  def getModuleTree(input: I): Tree[Node[Option[M]]]

  /**
   * A [[Map]] mapping from a key retrieved from the original build tool
   * (for example, the GAV coordinate for Maven, `ProjectRef.project` for `sbt`)
   * to the module FQN reference string in code such as `parentModule.childModule`.
   *
   * If there is no need for such a map, override it with [[Unit]].
   */
  type ModuleFqnMap
  def getModuleFqnMap(moduleNodes: Seq[Node[M]]): ModuleFqnMap

  def convert(
      input: I,
      cfg: C,
      shared: BuildGenUtil.BasicConfig
  ): Tree[Node[BuildObject]] = {
    val moduleTree = getModuleTree(input)

    // for resolving moduleDeps
    val moduleNodes =
      moduleTree.nodes().flatMap(node => node.value.map(m => node.copy(value = m))).toSeq
    val moduleRefMap = getModuleFqnMap(moduleNodes)

    val baseInfo = shared.baseModule.map {
      getBaseInfo(input, cfg, _, moduleNodes.size)
    }

    moduleTree.map(optionalBuild =>
      optionalBuild.copy(value =
        optionalBuild.value.fold(
          BuildObject(SortedSet("mill._"), SortedMap.empty, Seq("Module"), "", "")
        )(moduleModel => {
          val name = getArtifactId(moduleModel)
          println(s"converting module $name")

          val build = optionalBuild.copy(value = moduleModel)
          val inner = extractIrBuild(cfg, build, moduleRefMap)

          val isNested = optionalBuild.dirs.nonEmpty
          BuildObject(
            imports =
              BuildGenUtil.renderImports(
                shared.baseModule,
                isNested,
                extraImports
              ),
            companions =
              shared.depsObject.fold(SortedMap.empty[String, BuildObject.Constants])(name =>
                SortedMap((name, SortedMap(inner.scopedDeps.namedMvnDeps.toSeq*)))
              ),
            supertypes = getSupertypes(cfg, baseInfo, build),
            inner = BuildGenUtil.renderIrBuild(inner, baseInfo),
            outer =
              if (isNested || baseInfo.isEmpty) ""
              else BuildGenUtil.renderIrBaseInfo(baseInfo.get)
          )
        })
      )
    )
  }

  def extraImports: Seq[String]

  def getSupertypes(cfg: C, baseInfo: Option[IrBaseInfo], build: Node[M]): Seq[String]

  def getBaseInfo(
      input: I,
      cfg: C,
      baseModule: String,
      packagesSize: Int
  ): IrBaseInfo

  def getArtifactId(moduleModel: M): String

  def extractIrBuild(
      cfg: C,
      // baseInfo: IrBaseInfo, // `baseInfo` is no longer needed as we compare the `IrBuild` with `IrBaseInfo` in common code now.
      build: Node[M],
      moduleFqnMap: ModuleFqnMap
  ): IrModuleBuild
}

object BuildGenBase {
  trait MavenAndGradle extends BuildGenBase {
    type I = Tree[Node[M]]
    override def getModuleTree(input: Tree[Node[M]]): Tree[Node[Option[M]]] =
      input.map(node => node.copy(value = Some(node.value)))
    override def extraImports: Seq[String] = Seq()
  }
}
