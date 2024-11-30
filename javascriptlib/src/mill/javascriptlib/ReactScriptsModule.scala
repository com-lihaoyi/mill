package mill.javascriptlib
import mill.*
import os.*

// create-react-app: https://create-react-app.dev/docs/documentation-intro
trait ReactScriptsModule extends TypeScriptModule {
  override def npmDeps: T[Seq[String]] = Task {
    Seq(
      "react@18.3.1",
      "react-dom@18.3.1",
      "react-scripts@5.0.1",
      "typescript@4.9.5",
      "web-vitals@2.1.4",
      "@types/node@16.18.121",
      "@types/react@18.3.12",
      "@types/react-dom@18.3.1"
    )
  }

  override def npmDevDeps: T[Seq[String]] = Task {
    Seq(
      "@testing-library/jest-dom@5.17.0",
      "@testing-library/react@16.0.1",
      "@testing-library/user-event@13.5.0",
      "@types/jest@27.5.2"
    )
  }

  override def sources: Target[PathRef] = Task.Source(millSourcePath)

  def packageJestOptions: Target[ujson.Obj] = Task {
    ujson.Obj(
      "moduleNameMapper" -> ujson.Obj("^app/(.*)$" -> "<rootDir>/src/app/$1")
    )
  }

  def packageOptions: Target[Map[String, ujson.Value]] = Task {
    Map(
      "jest" -> packageJestOptions(),
      "browserslist" -> ujson.Obj("product" -> ujson.Arr(">0.2%", "not dead", "not op_mini all"))
    )
  }

  override def tsCompilerOptionsPaths: Target[Map[String, String]] =
    Task { Map("app/*" -> "src/app/*") }

  // build react project via react scripts
  override def compile: T[(PathRef, PathRef)] = Task {
    // copy src files
    os.copy(sources().path, Task.dest, mergeFolders = true)

    val npm = npmInstall().path

    copyNodeModules()

    val combinedPaths = tsCompilerOptionsPaths() ++ Seq(
      "*" -> npm / "node_modules",
      "typescript" -> npm / "node_modules" / "typescript"
    )

    val combinedCompilerOptions: Map[String, ujson.Value] = tsCompilerOptions() ++ Map(
      "declaration" -> ujson.Bool(false),
      "typeRoots" -> ujson.Arr(),
      "target" -> ujson.Str("es5"),
      "lib" -> ujson.Arr("dom", "dom.iterable", "esnext"),
      "allowJs" -> ujson.Bool(true),
      "skipLibCheck" -> ujson.Bool(true),
      "allowSyntheticDefaultImports" -> ujson.Bool(true),
      "strict" -> ujson.Bool(true),
      "forceConsistentCasingInFileNames" -> ujson.Bool(true),
      "noFallthroughCasesInSwitch" -> ujson.Bool(true),
      "module" -> ujson.Str("esnext"),
      "moduleResolution" -> ujson.Str("node"),
      "resolveJsonModule" -> ujson.Bool(true),
      "isolatedModules" -> ujson.Bool(true),
      "noEmit" -> ujson.Bool(true),
      "jsx" -> ujson.Str("react-jsx"),
      "baseUrl" -> ujson.Str("."),
      "paths" -> ujson.Obj.from(combinedPaths.map { case (k, v) => (k, ujson.Arr(s"$v/*")) })
    )

    // mk tsconfig.json
    os.write(
      Task.dest / "tsconfig.json",
      ujson.Obj(
        "compilerOptions" -> ujson.Obj.from(combinedCompilerOptions.toSeq),
        "include" -> ujson.Arr((sources().path / "src").toString)
      )
    )

    // mk package.json
    os.write(
      Task.dest / "package.json",
      ujson.Obj.from(packageOptions().toSeq)
    )

    (PathRef(Task.dest), PathRef(Task.dest / "build"))
  }

  override def bundle: Target[PathRef] = Task {
    val compiled = compile()._1.path
    os.call(
      ("node", compiled / "node_modules" / "react-scripts" / "bin" / "react-scripts.js", "build"),
      cwd = compiled,
      stdout = os.Inherit,
      env = mkENV()
    )

    compile()._2
  }

  override def mkENV =
    Task.Anon {
      Map("NODE_PATH" -> Seq(
        ".",
        compile()._1.path,
        compile()._1.path / "node_modules"
      ).mkString(":"))
    }

  private def copyNodeModules: Task[Unit] = Task.Anon {
    val nodeModulesPath = npmInstall().path / "node_modules"

    // Check if the directory exists and is not empty
    if (os.exists(nodeModulesPath) && os.list(nodeModulesPath).nonEmpty) {
      os.copy(nodeModulesPath, Task.dest / "node_modules")
    }
  }

  // react-script tests
  def test: Target[CommandResult] = Task {
    val compiled = compile()._1.path

    os.call(
      (
        "node",
        (compiled / "node_modules" / "react-scripts" / "bin" / "react-scripts.js").toString,
        "test",
        "--watchAll=false"
      ),
      cwd = compiled,
      stdout = os.Inherit,
      env = mkENV()
    )
  }

}
