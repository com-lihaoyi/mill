package mill.integration

import utest._



object GitbucketTests extends IntegrationTestSuite("MILL_GITBUCKET_REPO", "gitbucket") {
  val tests = Tests{
    initWorkspace()
    'compile - {
      assert(eval("gitbucket.compile"))
    }

  }
}
