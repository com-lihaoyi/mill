package mill.main.buildgen

import mill.main.buildgen.BuildGenUtil.{compactBuildTree, writeBuildObject}

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
    val moduleOptionTree = moduleTree.map(node => node.copy(value = node.value))

    // for resolving moduleDeps
    val moduleNodes =
      moduleOptionTree.nodes().flatMap(node => node.value.map(m => node.copy(value = m))).toSeq
    val moduleRefMap = getModuleFqnMap(moduleNodes)

    val baseInfo =
      shared.baseModule.fold(IrBaseInfo()) { getBaseInfo(input, cfg, _, moduleNodes.size) }

    moduleOptionTree.map(optionalBuild =>
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

  def getArtifactId(moduleModel: M): String

  def extractIrBuild(
      cfg: C,
      // baseInfo: IrBaseInfo, // `baseInfo` is no longer needed as we compare the `IrBuild` with `IrBaseInfo`/`IrTrait` in common code now.
      build: Node[M],
      moduleFqnMap: ModuleFqnMap
  ): IrBuild
}

object BuildGenBase {
  trait MavenAndGradle[M, D] extends BuildGenBase[M, D, Tree[Node[M]]] {
    override def getModuleTree(input: Tree[Node[M]]): Tree[Node[Option[M]]] =
      input.map(node => node.copy(value = Some(node.value)))
    override def extraImports: Seq[String] = Seq()
  }
}
