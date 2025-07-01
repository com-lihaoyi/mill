package mill

package object scalalib extends mill.scalalib.JsonFormatters {

  implicit class DepSyntax(ctx: StringContext) extends AnyVal {
    def mvn(args: Any*): Dep = Dep.parse {
      (
        ctx.parts.take(args.length).zip(args).flatMap { case (p, a) => Seq(p, a) } ++
          ctx.parts.drop(args.length)
      ).mkString
    }

    @deprecated("Use `mvn` string interpolator instead.", "Mill 0.12.11")
    def ivy(args: Any*): Dep = mvn(args*)
  }


  export mill.javalib.Assembly
  export mill.javalib.AssemblyModule
  export mill.javalib.CoursierModule
  export mill.javalib.Dep
  export mill.javalib.Dependency
  export mill.javalib.JavaHomeModule
  export mill.javalib.JavaModule
  export mill.javalib.JlinkModule
  export mill.javalib.JpackageModule
  export mill.javalib.JvmWorkerModule
  export mill.javalib.Lib
  export mill.javalib.MavenModule
  export mill.javalib.MvnDepsTreeArgs
  export mill.javalib.NativeImageModule
  export mill.javalib.PublishModule
  export mill.javalib.RunModule
  export mill.javalib.SonatypeCentralPublisher
  export mill.javalib.SonatypeCentralPublishModule
  export mill.javalib.TestModule
  export mill.javalib.TestModuleUtil
  export mill.javalib.WithJvmWorkerModule
}
