# Mill Gitlab module

Gitlab does not support http basic auth so using PublishModule, artifactory-
or bintray-module does not work. This module tries to provide as automatic
as possible support for gitlab package registries and automatic detection of
gitlab CI/CD pipeline.



## Publishing

Most trivial publish config is:

```scala worksheet
import mill._, scalalib._, mill.scalalib.publish._
import $ivy.`com.lihaoyi::mill-contrib-gitlab:`
import mill.contrib.gitlab._

object lib extends ScalaModule with GitlabPublishModule {
  // PublishModule requirements:
  def publishVersion = "0.0.1"
  def pomSettings = ??? // PomSettings(...)
  
  // GitlabPublishModule requirements
  // 42 is the project id in your gitlab
  def publishRepository = GitlabProjectRepository("https://gitlab.local", 42)
}
```

By default, module first tries to look for
personal access token, then deploy token and lastly ci job token. Default search order is

1) Environment variable `GITLAB_PERSONAL_ACCESS_TOKEN`
2) File `~/.mill/gitlab/personal-access-token`
3) Environment variable `GITLAB_DEPLOY_TOKEN`
4) File `~/.mill/gitlab/deploy-token`
5) Environment variable `CI_JOB_TOKEN`

Because contents of `CI_JOB_TOKEN`
environment variable is checked publishing should just work when run in Gitlab
CI/CD pipeline.

If you want something else than default environment configuration can be
overridden by defining
`def gitlabEnvironment: GitlabEnvironment = new GitlabEnvironment { ... }` in
a module extending `GitlabPublishModule`. Generally overriding `tokenSearchOrder` is 
enough but changing default locations is also possible. One can add new environment variables
to look from (`GitlabEnvironment.Env`), files (`GitlabEnvironment.File`) or run custom 
logic (`GitlabEnvironment.Custom`).

Example of custom GitlabEnvironment that uses all of the above to add to token search order:

```scala
override def gitlabEnvironment = new GitlabEnvironment {
  import GitlabEnvironment._ // For Env, File and Custom classes

  override def tokenSearchOrder: Seq[TokenSource] =
    super.tokenSearchOrder ++ Seq(
      Env("CUSTOM_ENV", token => GitlabToken("header", token)),
      File(os.Path("/path/to/gitlab-token"), token => GitlabToken.deployToken(token)),
      Custom(() => /* Run anything here */ Right(GitlabToken("header", "foo")))
    )
}
```

Modifying/overriding the environment with `Custom` should be powerful enough but if really 
necessary one can also override GitlabPublishModules `def gitlabToken: GitlabToken`.

For convenience GitlabPublishModule has `def skipPublish: Boolean` that defaults to `false`.


## Gitlab package registry dependency

Making mill to fetch package from gitlab package repository is simple:

```scala
// don't do this
def repositoriesTask = T.task {
  super.repositoriesTask() ++ Seq(
    MavenRepository("https://gitlab.local/api/v4/projects/42/packages/maven", 
      Some(Authentication(Seq(("Private-Token", "<<private-token>>"))))))
}
```

However, **we do not want to expose our secrets in our build configuration**. 
We would like to use the same authentication mechanisms when publishing. This extension
provides trait `GitlabMavenRepository` to ease that.

```scala worksheet
object myPackageRepository extends GitlabMavenRepository {
  // override def gitlabEnv = new GitlabEnvironment { /* Customize if needed, omit if unnecessary */ }
  // Needed. Can be GitlabProjectRepository or GitlabInstanceRepository
  def repository = GitlabGroupRepository("https://gitlab.local", "MY_GITLAB_GROUP")
}

// 
object myModule extends ScalaModule {
  // ...
  def repositoriesTask = T.task {
    super.repositoriesTask() ++ Seq(myPackageRepository.mavenRepo())
  }
}
```


### Why the intermediate `packageRepository` object?

Gitlab supports instance, group and project registries. When depending on 
multiple private packages is more convenient to depend on instance or 
group level registry. However, publishing is only possible to project registry 
and that is why `GitlabPublishModule` requires a `GitlabProjectRepository` instance. 

Names in `GitlabPublishModule` and `GitlabMavenRepository` do not clash, but it would 
be quite confusing to have similarly named defs from both. Better naming would help, but 
separate object makes repository config sharable between modules.




## Future development / caveats

- I'm confident that this is not suitable for all gitlab users.
- Some maven / gitlab feature I'm missing?
- Tuning GitlabMavenRepository.
  - need to support multiple registries?
  - prefer implemented trait in documentation?
- More configuration, timeouts etc
- Some other common token source I've overlooked
- Container registry support with docker module
- Other Gitlab auth methods? (deploy keys, ...)
- Tested with Gitlab 15.2.2. Older versions might not work 



## References 

- Mill contrib [artifactory](https://github.com/com-lihaoyi/mill/tree/main/contrib/artifactory/src/mill/contrib/artifactory)
  and [bintray](https://github.com/com-lihaoyi/mill/tree/main/contrib/bintray/src/mill/contrib/bintray) 
  modules source code
- [sbt-gitlab](https://github.com/azolotko/sbt-gitlab)
- Gitlab documentation
  - [maven package registry](https://docs.gitlab.com/ee/user/packages/maven_repository/index.html)
  - [Gitlab maven api](https://docs.gitlab.com/ee/api/packages/maven.html)
