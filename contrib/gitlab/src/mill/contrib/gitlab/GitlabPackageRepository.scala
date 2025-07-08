package mill.contrib.gitlab

import mill.javalib.publish.Artifact

sealed trait GitlabPackageRepository {
  def url(): String
}

// Could also support project name (https://docs.gitlab.com/ee/user/packages/maven_repository/index.html#project-level-maven-endpoint)
// though only ID can be used for publishing.
case class ProjectRepository(baseUrl: String, projectId: Int) extends GitlabPackageRepository {
  override def url(): String = baseUrl + s"/api/v4/projects/$projectId/packages/maven"

  // https://docs.gitlab.com/ee/api/packages/maven.html#upload-a-package-file
  def uploadUrl(artifact: Artifact): String = {
    val repoUrl = url()
    val group = artifact.group.replace(".", "/")
    val id = artifact.id
    val version = artifact.version
    s"$repoUrl/$group/$id/$version"
  }
}

// Note that group repository has some limitations:
// https://docs.gitlab.com/ee/user/packages/maven_repository/#group-level-maven-endpoint
case class GroupRepository(baseUrl: String, groupId: String) extends GitlabPackageRepository {
  override def url(): String = baseUrl + s"/api/v4/groups/$groupId/-/packages/maven"
}

// Note that instance level repo has some limitations:
// https://docs.gitlab.com/ee/user/packages/maven_repository/#instance-level-maven-endpoint
case class InstanceRepository(baseUrl: String) extends GitlabPackageRepository {
  override def url(): String = baseUrl + s"/api/v4/packages/maven"
}
