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
  override def publishVersion = "0.0.1"

  override def pomSettings = ??? // PomSettings(...)

  // GitlabPublishModule requirements
  // 42 is the project id in your gitlab
  override def publishRepository = ProjectRepository("https://gitlab.local", 42)
}
```

`publishVersion` and `pomSettings` come from `PublishModule`. Gitlab plugin requires you to 
set `publishRepository` for target of artifact publishing. Note that this *must* be a Gitlab
project repository defined by project id (publishing to other type of repositories is not
[supported](https://docs.gitlab.com/ee/user/packages/maven_repository/#use-the-gitlab-endpoint-for-maven-packages)).

You can also override `def gitlabTokenLookup: GitlabTokenLookup` if default token lookup 
does suit your needs. Configuring lookup is documented below.

### Default token lookup

By default, module first tries to look for
personal access token, then deploy token and lastly ci job token. Default search order is

1) Environment variable `GITLAB_PERSONAL_ACCESS_TOKEN`
2) System property `gitlab.personal-access-token`
3) File `~/.mill/gitlab/personal-access-token`
4) File `.gitlab/personal-access-token`
5) Environment variable `GITLAB_DEPLOY_TOKEN`
6) System property `gitlab.deploy-token`
7) File `~/.mill/gitlab/deploy-token`
8) File `.gitlab/deploy-token`
9) Environment variable `CI_JOB_TOKEN`

Items 1-4 are personal access tokens, 5-8 deploy tokens and 9 is job token.

Because contents of `CI_JOB_TOKEN`
environment variable is checked publishing should just work when run in Gitlab
CI/CD pipeline. If you want something else than default lookup configuration can be
overridden. There are different ways of configuring token resolving. 

### Configuring token lookup

#### Override default search places

If you want to change environment variable names, property names of paths where plugin looks
for token it can be done by overriding their respective values in `GitlabTokenLookup`. For 
example:
```scala
override tokenLookup: GitlabTokenLookup = new GitlabTokenLookup {
  override def personalTokenEnv = "MY_DEPLOY_TOKEN"
  override def deployTokenFile: os.Path = os.root /"etc"/"tokens"/"gitlab-deploy-token"
}
```

This still keeps the default search order, but allows to change places where to look.

#### Add or change tokenSearchOrder

If original search order is too wide, or you would like to add places to look you can override 
the `tokenSearchOrder`. Example below ignores default search order and adds five places 
to search from.

```scala
override tokenLookup: GitlabTokenLookup = new GitlabTokenLookup {
  // Just to add to default sequence set: super.tokenSearchOrder ++ Seq(...
  override def tokenSearchOrder: Seq[GitlabToken] = Seq(
    Personal(Env("MY_PRIVATE_TOKE")),
    Personal(Property("gitlab.private-token")),
    Deploy(File(os.root/"etc"/"gitlab-deploy-token")),
    Deploy(Custom(myCustomTokenSource)),
    CustomHeader("my-header", Custom(myCustomTokenSource))
  )
  
  def myCustomTokenSource(): Either[String, String] = Right("foo")
}
```

There are two things happening here. Gitlab needs right kind of token for right header. 
`Personal` creates "Private-Token" header and `Deploy` produces "Deploy-Token". Finally, 
any custom header can be set with `CustomHeader`. (`CIJob` also
exists, but it should not exist elsewhere than in environment variable `CI_JOB_TOKEN`)

After token type plugin needs information where to load token from. There are four possibilities
1) `Env`: From environment variable
2) `Property`: From system property
3) `File`: From file (content is trimmed, usually at least \n at the end is present)
4) `Custom`: Any function producing Either[String, String].

### Override search logic completely

Modifying the lookup order with `Custom` should be powerful enough but if really 
necessary one can also override GitlabPublishModules `gitlabHeaders`. 
If for some reason you need to set multiple headers this is currently the only way.

```scala
object myModule extends ScalaModule with GitlabPublishModule {
  override def gitlabHeaders(
      log: Logger,
      env: Map[String, String],  // Environment variables
      props: Map[String, String] // System properties
    ): GitlabAuthHeaders = {
    // any logic here
    GitlabAuthHeaders(Seq(
        "header1" -> "value1",
        "header2" -> "value2"
    ))
  }
}
```

### Other

For convenience GitlabPublishModule has `def skipPublish: Boolean` that defaults to `false`. 
This allows running CI/CD pipeline with fast way to skip publishing (for example if you
are not ready increase version number just yet).


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

`GitlabMavenRepository` has overridable `def tokenLookup: GitlabTokenLookup` and you can use
the same configuration mechanisms as described [above](#configuring-token-lookup).  

### Why the intermediate `packageRepository` object?

Nothing actually prevents you from implementing `GitlabMavenRepository` trait. Having 
a separate object makes configuration more sharable when you have multiple registries. 

Gitlab supports instance, group and project registries. When depending on 
multiple private packages is more convenient to depend on instance or 
group level registry. However, publishing is only possible to project registry 
and that is why `GitlabPublishModule` requires a `GitlabProjectRepository` instance. 


## Future development / caveats

- I'm confident that this is not suitable for all gitlab users.
- Some maven / gitlab feature I'm missing?
- Tuning GitlabMavenRepository.
  - need to support multiple registries?
  - prefer implemented trait in documentation?
- More configuration, timeouts etc
- Some other common token source / type I've overlooked
- Container registry support with docker module
- Other Gitlab auth methods? (deploy keys?, ...)
- Tested with Gitlab 15.2.2. Older versions might not work 
- Tested with ScalaModule, others should work.



## References 

- Mill contrib [artifactory](https://github.com/com-lihaoyi/mill/tree/main/contrib/artifactory/src/mill/contrib/artifactory)
  and [bintray](https://github.com/com-lihaoyi/mill/tree/main/contrib/bintray/src/mill/contrib/bintray) 
  modules source code
- [sbt-gitlab](https://github.com/azolotko/sbt-gitlab)
- Gitlab documentation
  - [maven package registry](https://docs.gitlab.com/ee/user/packages/maven_repository/index.html)
  - [Gitlab maven api](https://docs.gitlab.com/ee/api/packages/maven.html)
