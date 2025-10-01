package mill.main.maven

import mainargs.{Flag, ParserForClass, arg, main}
import mill.main.buildgen.*
import mill.main.buildgen.BuildConventions.*
import org.apache.maven.model.*

import scala.jdk.CollectionConverters.*

/**
 * Converts a Maven build by generating module configurations from POM files.
 * @see [[MavenBuildGenArgs Command line arguments]]
 */
object MavenBuildGenMain {

  def main(args: Array[String]): Unit = {
    val args0 = summon[ParserForClass[MavenBuildGenArgs]].constructOrExit(args.toSeq)
    println("converting Maven build")
    import args0.*

    val modeler = Modeler()
    val segmentsResults = Tree.from(os.sub) { sub =>
      val result = modeler.read(os.pwd / sub)
      (
        (sub.segments, result),
        result.getEffectiveModel.getModules.iterator.asScala.map(s => sub / os.SubPath(s)).toSeq
      )
    }
    val segmentsByGav = segmentsResults.iterator.map { (segments, result) =>
      val model = result.getEffectiveModel
      ((model.getGroupId, model.getArtifactId, model.getVersion), segments)
    }.toMap

    val packages = segmentsResults.iterator.map { (segments, result) =>
      val model = result.getEffectiveModel
      val rawModel = result.getRawModel

      def isBom(dep: Dependency) = isBomDep(dep.getGroupId, dep.getArtifactId)
      def dependencies(scopes: Seq[String]) = model.getDependencies.iterator.asScala
        .filter(dep => scopes.contains(dep.getScope))
      def mvnDeps(scopes: String*) = dependencies(scopes).collect {
        case dep if !segmentsByGav.contains(toGav(dep)) && !isBom(dep) => toMvnDep(dep)
      }.toSeq
      def bomMvnDeps(scopes: String*) = dependencies(scopes).collect {
        case dep if !segmentsByGav.contains(toGav(dep)) && isBom(dep) => toMvnDep(dep)
      }.toSeq
      def moduleDeps(scopes: String*) = dependencies(scopes)
        .map(toGav).collect(segmentsByGav).map(ModuleConfig.ModuleDep(_)).toSeq

      val coursierModuleConfig = model.getRepositories.iterator.asScala.collect {
        case repo if repo.getId != "central" => repo.getUrl
      }.toSeq match {
        case Nil => None
        case repositories => Some(CoursierModuleConfig(repositories))
      }
      val (errorProneModuleConfig, javacOptions) = findErrorProneModuleConfigJavacOptions(
        Plugins.javacOptions(model),
        // TODO Filter known error-prone deps
        Plugins.javacAnnotationProcessorMvnDeps(model)
      )
      val javaHomeModuleConfig = findJavaHomeModuleConfig(Plugins.javaVersion(model), javacOptions)
      val javaModuleConfig = JavaModuleConfig(
        mvnDeps = mvnDeps("compile"),
        compileMvnDeps = mvnDeps("provided"),
        runMvnDeps = mvnDeps("runtime"),
        bomMvnDeps = bomMvnDeps("compile"),
        moduleDeps = moduleDeps("compile"),
        compileModuleDeps = moduleDeps("provided"),
        runModuleDeps = moduleDeps("runtime"),
        javacOptions = javacOptions,
        artifactName = overrideArtifactName(model.getArtifactId, segments)
      )
      val publishModuleConfig = Option.when(!Plugins.skipDeploy(model)):
        PublishModuleConfig(
          pomPackagingType = overridePomPackagingType(model.getPackaging),
          pomParentProject = toPomParentProject(model.getParent),
          pomSettings = toPomSettings(model),
          publishVersion = model.getVersion,
          publishProperties = rawModel.getProperties.asScala.toMap
        )
      val configs = javaModuleConfig +: Seq(
        publishModuleConfig,
        errorProneModuleConfig,
        javaHomeModuleConfig,
        coursierModuleConfig
      ).flatten

      val testModule = {
        // "provided" scope is for both compilation and testing
        val mvnDeps0 = mvnDeps("test", "provided")
        findTestModuleMixin(mvnDeps0).map { mixin =>
          val testConfigs = Seq(
            JavaModuleConfig(
              mvnDeps = mvnDeps0,
              bomMvnDeps = bomMvnDeps("test"),
              moduleDeps = moduleDeps("test", "provided")
            ),
            RunModuleConfig(
              forkWorkingDir = "moduleDir"
            )
          ) ++ errorProneModuleConfig
          TestModuleRepr(
            supertypes = Seq("MavenTests"),
            mixins = Seq(mixin),
            configs = testConfigs,
            testParallelism = false,
            testSandboxWorkingDir = false
          )
        }
      }

      val supertypes = Seq("MavenModule") ++
        (if (publishModuleConfig.isEmpty) Nil else Seq("PublishModule")) ++
        (if (errorProneModuleConfig.isEmpty) Nil else Seq("ErrorProneModule"))
      val module = ModuleRepr(
        segments = segments,
        supertypes = supertypes,
        configs = configs,
        testModule = testModule
      )
      Tree(module)
    }.toSeq

    val mavenJvmOpts = {
      val file = os.pwd / ".mvn/jvm.config"
      if (os.isFile(file))
        os.read.lines.stream(file).map(_.trim).filter(_.nonEmpty).flatMap(_.split(" ")).toSeq
      else Nil
    }

    var build = BuildRepr.fill(packages).copy(millJvmOpts = mavenJvmOpts)
    if (merge.value) build = build.merged
    if (!noMeta.value) build = build.withMetaBuild
    BuildWriter(build).writeFiles()
  }

  def toGav(dep: Dependency) = (dep.getGroupId, dep.getArtifactId, dep.getVersion)

  def toMvnDep(dep: Dependency) = {
    import dep.*
    ModuleConfig.MvnDep(
      organization = getGroupId,
      name = getArtifactId,
      version = Option(getVersion),
      // prevent evaluation of dynamic values, such as ${os.detected.name}, in generated build
      classifier = Option(getClassifier).map(_.replace("$", "$$")),
      `type` = Option(getType),
      excludes = getExclusions.asScala.map(x => (x.getGroupId, x.getArtifactId)).toSeq
    )
  }

  def toPomParentProject(parent: Parent) = {
    Option.when(parent != null):
      import parent.*
      ModuleConfig.Artifact(getGroupId, getArtifactId, getVersion)
  }

  def toPomSettings(model: Model) = {
    import model.*
    ModuleConfig.PomSettings(
      description = getDescription,
      organization = getGroupId,
      url = getUrl,
      licenses = getLicenses.iterator.asScala.map(toLicense).toSeq,
      versionControl = toVersionControl(getScm),
      developers = getDevelopers.iterator.asScala.map(toDeveloper).toSeq
    )
  }

  def toLicense(license: License) = {
    import license.*
    ModuleConfig.License(
      name = getName,
      url = getUrl,
      distribution = getDistribution
    )
  }

  def toVersionControl(scm: Scm) = {
    if (scm == null) ModuleConfig.VersionControl()
    else
      import scm.*
      ModuleConfig.VersionControl(
        browsableRepository = Option(getUrl),
        connection = Option(getConnection),
        developerConnection = Option(getDeveloperConnection),
        tag = Option(getTag)
      )
  }

  def toDeveloper(developer: Developer) = {
    import developer.*
    ModuleConfig.Developer(
      id = getId,
      name = getName,
      url = getUrl,
      organization = Option(getOrganization),
      organizationUrl = Option(getOrganizationUrl)
    )
  }
}

@mainargs.main
case class MavenBuildGenArgs(
    @mainargs.arg(doc = "merge generated build files")
    merge: mainargs.Flag,
    @mainargs.arg(doc = "disable generating meta-build files")
    noMeta: mainargs.Flag
)
object MavenBuildGenArgs {
  given ParserForClass[MavenBuildGenArgs] = ParserForClass.apply
}
