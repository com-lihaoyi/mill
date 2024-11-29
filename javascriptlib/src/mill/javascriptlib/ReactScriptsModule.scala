package mill.javascriptlib
import mill._
import os.*
import mill.define.Target

// create-react-app: https://create-react-app.dev/docs/documentation-intro
trait CreateReactAppModule extends TypeScriptModule {
  override def npmDeps: T[Seq[String]] = Task {
    Seq(
      "react@18.3.1",
      "react-dom@18.3.1",
      "react-scripts@5.0.1",
      "typescript@4.9.5",
      "web-vitals@2.1.4",
      "@types/node@16.18.121",
      "@types/react@18.3.12",
      "@types/react-dom@18.3.1",
    )
  }

  override def npmDevDeps: T[Seq[String]] = Task {
    Seq(
      "@testing-library/jest-dom@5.17.0",
      "@testing-library/react@16.0.1",
      "@testing-library/user-event@13.5.0",
      "@types/jest@27.5.2",
      "serve@12.0.1"
    )
  }

  override def sources: Target[PathRef] = Task.Source(millSourcePath)

  def setup: Task[Unit] = Task.Anon {
    // copy src files
    os.copy(sources().path, Task.dest, mergeFolders = true)

    def buildDependencies(dependencies: Seq[String]): ujson.Obj = {
      val dependenciesObj = dependencies.map { dep =>
        val lastAtIndex = dep.lastIndexOf('@')
        if (lastAtIndex == -1)
          dep -> ujson.Str("latest") // No '@' found, default version to "latest"; you probably should always provide the dependency version
        else {
          val name = dep.substring(0, lastAtIndex)
          val version = dep.substring(lastAtIndex + 1)
          name -> ujson.Str(version)
        }
      }
      ujson.Obj.from(dependenciesObj)
    }

    val allPaths = Seq(
      "*" -> Task.dest / "node_modules",
      "app/*" -> "src/app/*",
      "typescript" -> Task.dest / "node_modules" / "typescript"
    )

    // mk tsconfig.json
    os.write(
      Task.dest / "tsconfig.json",
      ujson.Obj(
        "compilerOptions" -> ujson.Obj(
          "target" -> "es5",
          "lib" -> ujson.Arr("dom", "dom.iterable", "esnext"),
          "allowJs" -> true,
          "skipLibCheck" -> true,
          "esModuleInterop" -> true,
          "allowSyntheticDefaultImports" -> true,
          "strict" -> true,
          "forceConsistentCasingInFileNames" -> true,
          "noFallthroughCasesInSwitch" -> true,
          "module" -> "esnext",
          "typeRoots" -> ujson.Arr((Task.dest / "node_modules" / "@types").toString),
          "moduleResolution" -> "node",
          "resolveJsonModule" -> true,
          "isolatedModules" -> true,
          "noEmit" -> true,
          "jsx" -> "react-jsx",
          "baseUrl" -> ".",
          "paths" -> ujson.Obj.from(allPaths.map { case (k, v) => (k, ujson.Arr(s"$v/*")) })
        ),
        "include" -> ujson.Arr((sources().path / "src").toString)
      )
    )

    // mk package.json
    os.write(
      Task.dest / "package.json",
      ujson.Obj(
        "name" -> "foo",
        "version" -> "0.1.0",
        "private" -> true,
        "jest" -> ujson.Obj(
          "moduleNameMapper" -> ujson.Obj(
            "^app/(.*)$" -> "<rootDir>/src/app/$1"
          )
        ),
        "dependencies" -> buildDependencies(transitiveNpmDeps()),
        "devDependencies" -> buildDependencies(transitiveNpmDevDeps()),
        "scripts" -> ujson.Obj(
          "build" -> s"${Task.dest / "node_modules" / "react-scripts" / "bin" / "react-scripts.js"} build",
          "test" -> s"${Task.dest / "node_modules" / "react-scripts" / "bin" / "react-scripts.js"} test --watchAll=false"
        ),
        "eslintConfig" -> ujson.Obj(
          "extends" -> ujson.Arr("react-app", "react-app/jest")
        ),
        "browserslist" -> ujson.Obj(
          "production" -> ujson.Arr(">0.2%", "not dead", "not op_mini all"),
          "development" -> ujson.Arr(
            "last 1 chrome version",
            "last 1 firefox version",
            "last 1 safari version"
          )
        )
      )
    )
  }

  def copyNodeModules: Task[Unit] = Task.Anon {
    val nodeModulesPath = npmInstall().path / "node_modules"

    // Check if the directory exists and is not empty
    if (os.exists(nodeModulesPath) && os.list(nodeModulesPath).nonEmpty) {
      os.copy(nodeModulesPath, Task.dest / "node_modules")
      println(s"Copied $nodeModulesPath to ${Task.dest / "node_modules"}")
    } else
      println(
        s"The directory $nodeModulesPath does not exist or is empty. Skipping copy operation."
      )
  }

  def build: T[PathRef] = Task {
    copyNodeModules()
    setup()
    val env =
      Map("NODE_PATH" -> (Task.dest / "node_modules").toString)

    os.call(
      ("npm", "run", "build"),
      stdout = os.Inherit,
      env = env
    )
    PathRef(Task.dest)
  }

  def test: T[PathRef] = Task {
    copyNodeModules()
    setup()
    val env =
      Map("NODE_PATH" -> (Task.dest / "node_modules").toString)

    os.call(
      ("npm", "run", "test"),
      stdout = os.Inherit,
      env = env
    )
    PathRef(Task.dest)
  }

  // serve static Html page
  def run: Target[CommandResult] = Task {
    val pathToBuild = build().path
    val env = Map("NODE_PATH" -> Seq(".", pathToBuild, pathToBuild / "node_modules").mkString(":"))
    os.call(
      (
        (pathToBuild / "node_modules" / "serve" / "bin" / "serve.js").toString,
        "-s",
        (pathToBuild / "build").toString,
        "-l",
        "3000"
      ),
      stdout = os.Inherit,
      env = env
    )
  }

}
