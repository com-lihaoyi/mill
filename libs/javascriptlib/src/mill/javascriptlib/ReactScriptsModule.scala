package mill.javascriptlib
import mill.*
import os.*

// create-react-app: https://create-react-app.dev/docs/documentation-intro
trait ReactScriptsModule extends TypeScriptModule {
  override def tsDeps: T[Seq[String]] = Task {
    Seq(
      "@types/node@16.18.121",
      "typescript@4.9.5"
    )
  }

  override def npmDeps: T[Seq[String]] = Task {
    Seq(
      "react@18.3.1",
      "react-dom@18.3.1",
      "react-scripts@5.0.1",
      "web-vitals@2.1.4",
      "@types/react@18.3.12",
      "@types/react-dom@18.3.1"
    )
  }

  override def npmDevDeps: T[Seq[String]] = Task {
    Seq(
      "@testing-library/jest-dom@5.17.0",
      "@testing-library/react@16.0.1",
      "@testing-library/user-event@13.5.0",
      "@types/jest@27.5.2",
      "@babel/plugin-proposal-private-property-in-object@7.21.11"
    )
  }

  override def sources: T[Seq[PathRef]] = Task.Sources(moduleDir)

  def packageJestOptions: T[ujson.Obj] = Task {
    ujson.Obj(
      "moduleNameMapper" -> ujson.Obj("^app/(.*)$" -> "<rootDir>/src/app/$1")
    )
  }

  def packageOptions: T[Map[String, ujson.Value]] = Task {
    Map(
      "jest" -> packageJestOptions(),
      "browserslist" -> ujson.Obj("product" -> ujson.Arr(">0.2%", "not dead", "not op_mini all"))
    )
  }

  override def compilerOptions: T[Map[String, ujson.Value]] = Task {
    Map(
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
      "baseUrl" -> ujson.Str(".")
    )
  }

  override def compilerOptionsPaths: T[Map[String, String]] =
    Task { Map("app/*" -> "src/app/*") }

  override def compilerOptionsBuilder: Task[Map[String, ujson.Value]] = Task.Anon {
    val npm = npmInstall().path
    val combinedPaths = compilerOptionsPaths() ++ Seq(
      "*" -> npm / "node_modules",
      "typescript" -> npm / "node_modules" / "typescript"
    )

    val combinedCompilerOptions: Map[String, ujson.Value] = compilerOptions() ++ Map(
      "paths" -> ujson.Obj.from(combinedPaths.map { case (k, v) => (k, ujson.Arr(s"$v/*")) })
    )

    combinedCompilerOptions
  }

  // build react project via react scripts
  override def compile: T[PathRef] = Task {
    // copy src files
    sources().foreach(source => os.copy(source.path, Task.dest, mergeFolders = true))
    copyNodeModules()

    // mk tsconfig.json
    os.write(
      Task.dest / "tsconfig.json",
      ujson.Obj(
        "compilerOptions" -> ujson.Obj.from(compilerOptionsBuilder().toSeq),
        "include" -> ujson.Arr(sources().map(source => (source.path / "src").toString))
      )
    )

    // mk package.json
    os.write(
      Task.dest / "package.json",
      ujson.Obj.from(packageOptions().toSeq)
    )

    PathRef(Task.dest)
  }

  override def bundle: T[PathRef] = Task {
    val compiled = compile().path
    os.call(
      ("node", compiled / "node_modules/react-scripts/bin/react-scripts.js", "build"),
      cwd = compiled,
      stdout = os.Inherit,
      env = forkEnv()
    )

    PathRef(compiled / "build")
  }

  override def forkEnv =
    Task {
      Map("NODE_PATH" -> Seq(
        ".",
        compile().path,
        compile().path / "node_modules"
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
  def test: T[CommandResult] = Task {
    val compiled = compile().path

    os.call(
      (
        "node",
        (compiled / "node_modules/react-scripts/bin/react-scripts.js").toString,
        "test",
        "--watchAll=false"
      ),
      cwd = compiled,
      stdout = os.Inherit,
      env = forkEnv()
    )
  }

}
