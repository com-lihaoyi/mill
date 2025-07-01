package mill.scalalib

lazy val Assembly = mill.javalib.Assembly
type Assembly = mill.javalib.Assembly

lazy val AssemblyModule = mill.javalib.AssemblyModule
type AssemblyModule = mill.javalib.AssemblyModule

type BomModule = mill.javalib.BomModule

lazy val BoundDep = mill.javalib.BoundDep
type BoundDep = mill.javalib.BoundDep

lazy val CoursierModule = mill.javalib.CoursierModule
type CoursierModule = mill.javalib.CoursierModule

lazy val Dep = mill.javalib.Dep
type Dep = mill.javalib.Dep

object Dependency extends ExternalModule.Alias(mill.javalib.Dependency)

// lazy val GenIdeaModule = mill.javalib.GenIdeaModule
type GenIdeaModule = mill.javalib.GenIdeaModule

// lazy val JavaHomeModule = mill.javalib.JavaHomeModule
type JavaHomeModule = mill.javalib.JavaHomeModule

lazy val JavaModule = mill.javalib.JavaModule
type JavaModule = mill.javalib.JavaModule

// lazy val JlinkModule = mill.javalib.JlinkModule
type JlinkModule = mill.javalib.JlinkModule

// lazy val JpackageModule = mill.javalib.JpackageModule
type JpackageModule = mill.javalib.JpackageModule

lazy val JsonFormatters = mill.javalib.JsonFormatters
type JsonFormatters = mill.javalib.JsonFormatters

lazy val JvmWorkerModule = mill.javalib.JvmWorkerModule
type JvmWorkerModule = mill.javalib.JvmWorkerModule

lazy val Lib = mill.javalib.Lib
// type Lib = mill.javalib.Lib

// lazy val MavenModule = mill.javalib.MavenModule
type MavenModule = mill.javalib.MavenModule

lazy val MvnDepsTreeArgs = mill.javalib.MvnDepsTreeArgs
type MvnDepsTreeArgs = mill.javalib.MvnDepsTreeArgs

// lazy val NativeImageModule = mill.javalib.NativeImageModule
type NativeImageModule = mill.javalib.NativeImageModule

lazy val OfflineSupport = mill.javalib.OfflineSupport
// type OfflineSupport = mill.javalib.OfflineSupport

// lazy val OfflineSupportModule = mill.javalib.OfflineSupportModule
type OfflineSupportModule = mill.javalib.OfflineSupportModule

lazy val PublishModule = mill.javalib.PublishModule
type PublishModule = mill.javalib.PublishModule

lazy val RunModule = mill.javalib.RunModule
type RunModule = mill.javalib.RunModule

// lazy val SonatypeCentralPublisher = mill.javalib.SonatypeCentralPublisher.self
type SonatypeCentralPublisher = mill.javalib.SonatypeCentralPublisher

object SonatypeCentralPublishModule extends ExternalModule.Alias(mill.javalib.SonatypeCentralPublishModule)

lazy val TestModule = mill.javalib.TestModule
type TestModule = mill.javalib.TestModule

lazy val TestModuleUtil = mill.javalib.TestModuleUtil
// type TestModuleUtil = mill.javalib.TestModuleUtil

// lazy val WithJvmWorkerModule = mill.javalib.WithJvmWorkerModule
type WithJvmWorkerModule = mill.javalib.WithJvmWorkerModule
