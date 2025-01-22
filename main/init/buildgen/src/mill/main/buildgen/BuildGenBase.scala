package mill.main.buildgen

import mill.main.buildgen.BuildGenUtil.{BaseInfo, buildPackages, convertToBuildObject}

trait BuildGenBase[M, D, C] {
  def convert(
      input: Tree[Node[M]],
      cfg: C,
      shared: BuildGenUtil.Config
  ): Tree[Node[BuildObject]] = {
    // for resolving moduleDeps
    val moduleSupertypes = getModuleSupertypes(cfg)
    val packages = buildPackages(input)(getPackage)

    val baseInfo = shared.baseModule match {
      case None => BaseInfo()
      case Some(baseModule) => getBaseInfo(input, cfg, baseModule, moduleSupertypes, packages.size)
    }

    input.map { build =>
      val name = getArtifactId(build.value)
      println(s"converting module $name")

      val supertypes = getSuperTypes(cfg, baseInfo, build)

      val scopedDeps = extractScopedDeps(build.value, packages, cfg)

      val inner = IrBuild(
        scopedDeps = scopedDeps,
        testModule = shared.testModule,
        hasTest = os.exists(getMillSourcePath(build.value) / "src/test"),
        dirs = build.dirs,
        repos = getRepositories(build.value).diff(baseInfo.repos),
        javacOptions = {
          val options = getJavacOptions(build.value).diff(baseInfo.javacOptions)
          if (options == baseInfo.javacOptions) Seq.empty else options
        },
        projectName = name,
        pomSettings = if (baseInfo.noPom) extractPomSettings(build.value) else null,
        publishVersion = {
          val version = getPublishVersion(build.value)
          if (version == baseInfo.publishVersion) null else version
        },
        packaging = getPackaging(build.value),
        pomParentArtifact = getPomParentArtifact(build.value),
        resources = getResources(build.value),
        testResources = getTestResources(build.value),
        publishProperties = getPublishProperties(build.value, cfg, baseInfo)
      )

      val isNested = build.dirs.nonEmpty
      build.copy(value =
        BuildObject(
          BuildGenUtil.renderImports(shared.baseModule, isNested, packages.size),
          inner.scopedDeps.companions,
          supertypes,
          BuildGenUtil.renderIrBuild(inner),
          if (isNested || baseInfo.moduleTypedef == null) ""
          else BuildGenUtil.renderIrTrait(baseInfo.moduleTypedef)
        )
      )
    }
  }
  def getSuperTypes(cfg: C, baseInfo: BaseInfo, build: Node[M]): Seq[String]
  def extractPomSettings(model: M): IrPom
  def extractScopedDeps(
      model: M,
      packages: PartialFunction[(String, String, String), String],
      cfg: C
  ): IrScopedDeps
  def groupArtifactVersion(dep: D): (String, String, String)

  def getBaseInfo(
      input: Tree[Node[M]],
      cfg: C,
      baseModule: String,
      moduleSupertypes: Seq[String],
      packagesSize: Int
  ): BaseInfo
  def getPackage(model: M): (String, String, String)

  def getModuleSupertypes(cfg: C): Seq[String]
  def getRepositories(project: M): Seq[String]
  def getArtifactId(model: M): String
  def getMillSourcePath(m: M): os.Path

  def getPackaging(project: M): String
  def getPomParentArtifact(project: M): IrArtifact
  def getPublishVersion(project: M): String
  def getJavacOptions(project: M): Seq[String]
  def getResources(m: M): Seq[os.SubPath]
  def getTestResources(m: M): Seq[os.SubPath]
  def getPublishProperties(m: M, c: C, baseInfo: BaseInfo): Seq[(String, String)]
}
