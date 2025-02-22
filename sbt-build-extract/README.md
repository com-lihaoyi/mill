# sbt-build-extract

Extracts build structure of sbt projects.

## Usage

Add to your `project/plugins.sbt`:
```scala
addSbtPlugin("com.lihaoyi" % "sbt-build-extract" % "0.0.1")
libraryDependencies += "com.lihaoyi" %% "sbt-build-extract-core" % "0.0.1"
```

then run
```bash
sbt> exportBuildStructure
```

This will generate files inside `target/build-export` folder, for example:
```
myproject1.json
myproject2.json
```

Each of these files contains the build structure of a subproject in JSON format.
For example if `myproject1` depends on `myproject2`, the `myproject1.json` file will contain something like this:
```json
{
    "artifactClassifier": null,
    "artifactName": "myproject1",
    "artifactType": "jar",
    "base": "/projects/myproject1",
    "description": "my project 1",
    "developers": [
        {
          "email": "..",
          "id": "..",
          "name": "..",
          "url": ".."
        }
    ],
    "externalDependencies": [
      {
        "organization": "org.scala-lang",
        "name": "scala3-library",
        "revision": "3.3.4",
        "extraAttributes": {},
        "configurations": null,
        "excludes": [],
        "crossVersion": "binary"
      },
      {
        "organization": "org.scalameta",
        "name": "munit",
        "revision": "1.0.2",
        "extraAttributes": {},
        "configurations": "test",
        "excludes": [],
        "crossVersion": "binary"
      }
    ],
    "homepage": "..",
    "id": "myproject1",
    "interProjectDependencies": [
        {
            "project": "myproject2",
            "configuration": "default"
        }
    ],
    "javacOptions": [
    ],
    "licenses": [
        {
          "name": "Apache-2.0",
          "url": "http://www.apache.org/licenses/LICENSE-2.0"
        }
    ],
    "name": "proj1",
    "organization": "com.lihaoyi",
    "repositories": [
      "https://oss.sonatype.org/content/repositories/snapshots"
    ],
    "scalacOptions": [
        "-deprecation",
        "-Yretain-trees",
        "-Wunused:all"
    ],
    "scalaVersion": "3.3.4",
    "scmInfo": {
        "browseUrl": "..",
        "connection": "scm:git:git@github.com:..",
        "devConnection": null
    },
    "version": "0.0.1-SNAPSHOT"
}
```
