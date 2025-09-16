package mill.main.buildgen

import mill.main.buildgen.BuildGenUtil.{compactBuildTree, writeBuildObject}

import scala.collection.immutable.{SortedMap, SortedSet}

trait BuildGenBase {
  type Module
  type Input
  // not renamed to `Config` as it would otherwise conflict with the concrete nested `Config` classes inside
  type C

  def convertWriteOut(cfg: C, shared: BuildGenUtil.BasicConfig, input: Input): Unit = {
    val output = convert(input, cfg, shared)
    writeBuildObject(
      if (shared.merge.value) compactBuildTree(output) else output,
      shared.jvmId
    )
  }

  def getModuleTree(input: Input): Tree[Node[Option[Module]]]

  /**
   * A [[Map]] mapping from a key retrieved from the original build tool
   * (for example, the GAV coordinate for Maven, `ProjectRef.project` for `sbt`)
   * to the module FQN reference string in code such as `parentModule.childModule`.
   *
   * If there is no need for such a map, override it with [[Unit]].
   */
  type ModuleFqnMap

  def getModuleFqnMap(moduleNodes: Seq[Node[Module]]): ModuleFqnMap

  def convert(
      input: Input,
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
          val inner = extractIrModuleBuild(cfg, build, moduleRefMap)

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
            inner = BuildGenUtil.renderIrModuleBuild(inner, baseInfo),
            outer =
              if (isNested || baseInfo.isEmpty) ""
              else BuildGenUtil.renderIrBaseInfo(baseInfo.get)
          )
        })
      )
    )
  }

  def extraImports: Seq[String]

  def getSupertypes(cfg: C, baseInfo: Option[IrBaseInfo], build: Node[Module]): Seq[String]

  def getBaseInfo(
      input: Input,
      cfg: C,
      baseModule: String,
      packagesSize: Int
  ): IrBaseInfo

  def getArtifactId(moduleModel: Module): String

  def extractIrModuleBuild(
      cfg: C,
      // baseInfo: IrBaseInfo, // `baseInfo` is no longer needed as we compare the `IrModuleBuild` with `IrBaseInfo` in common code now.
      build: Node[Module],
      moduleFqnMap: ModuleFqnMap
  ): IrModuleBuild
}

object BuildGenBase {
  trait MavenAndGradle extends BuildGenBase {
    type Input = Tree[Node[Module]]

    override def getModuleTree(input: Tree[Node[Module]]): Tree[Node[Option[Module]]] =
      input.map(node => node.copy(value = Some(node.value)))
    override def extraImports: Seq[String] = Seq()
  }
}
