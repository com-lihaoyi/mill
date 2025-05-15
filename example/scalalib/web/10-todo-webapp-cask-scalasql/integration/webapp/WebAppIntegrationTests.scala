package webapp

import utest._
import requests._
import com.dimafeng.testcontainers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import scala.concurrent.blocking
import scala.sys.process._
import scala.concurrent.duration._
import scala.util.Try

object WebAppIntegrationTests extends TestSuite {

  val tests = Tests {
    var serverPid: Option[Process] = None
    val serverPort = 8080

    val pgContainer = PostgreSQLContainer(
      dockerImageNameOverride = DockerImageName.parse("postgres:15"),
      databaseName = "testdb",
      username = "postgres",
      password = "password"
    )

    def waitForServer(port: Int, timeout: FiniteDuration = 100.seconds): Boolean = {
      val deadline = timeout.fromNow
      while (deadline.hasTimeLeft()) {
        Try(requests.get(s"http://localhost:$port/")).toOption match {
          case Some(response) if response.statusCode == 200 => return true
          case _ =>
            Thread.sleep(500)
        }
      }
      false
    }

    test("start postgres container and app server") {
      pgContainer.start()

      val envVars = Seq(
        "DB_URL" -> pgContainer.jdbcUrl,
        "DB_USER" -> pgContainer.username,
        "DB_PASS" -> pgContainer.password
      )

      val cmd = Seq("mill", "runBackground")
      val pb = Process(cmd, None, envVars: _*)
      serverPid = Some(pb.run())

      val started = waitForServer(serverPort)
      require(started, "Server did not start in time")
    }

    test("add and list todos") {
      val response = requests.post(s"http://localhost:$serverPort/add/all", data = "")
      assert(response.statusCode == 200)
      assert(response.text.contains("What needs to be done"))

      val response2 = requests.post(s"http://localhost:$serverPort/list/all", data = "")
      assert(response2.text.contains("What needs to be done"))
    }

    test("toggle and delete todo") {
      // Toggle doesn't change DB but should still return 200
      val response = requests.post(s"http://localhost:$serverPort/toggle/all/1", data = "")
      assert(response.statusCode == 200)

      val deleteResp = requests.post(s"http://localhost:$serverPort/delete/all/1", data = "")
      assert(deleteResp.statusCode == 200)
    }

    test("cleanup") {
      serverPid.foreach(_.destroy())
      pgContainer.stop()
    }
  }
}
