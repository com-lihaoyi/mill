# sbt-multi-project-example

The goal of this example is to provide a multi-project build using `sbt` providing:
* A single `build.sbt` file which allows for centralized configuration, dependency and build management
* Each sub-project contains only its source code
* Sub-projects can depend on other sub-projects
* Only *deliverable* sub-projects produce a *fat-jar* using [sbt-assembly](https://github.com/sbt/sbt-assembly)

# Example structure
* sbt-multi-project-example/
    * common/
        * src/
        * test/
    * multi1/
        * src/
        * test/
    * multi2/
        * src/
        * test/
    * project/
        * build.properties
        * plugins.sbt
    * build.sbt
