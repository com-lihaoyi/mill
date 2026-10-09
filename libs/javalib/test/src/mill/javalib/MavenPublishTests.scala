package mill.javalib

import mill.api.PathRef
import mill.api.Logger.DummyLogger
import mill.javalib.MavenWorkerSupport.RemoteM2Publisher
import mill.javalib.PublishModule.PublishData
import mill.javalib.publish.Artifact
import utest.*

object MavenPublishTests extends TestSuite, MavenPublish {

  /** Records the deploy operations that [[MavenPublish]] asks the worker to perform. */
  private class RecordingWorker extends mill.javalib.internal.MavenWorkerSupport.Api {
    var remoteDeploys: Vector[(String, Vector[RemoteM2Publisher.M2Artifact])] = Vector.empty
    var localDeploys: Vector[(os.Path, Vector[RemoteM2Publisher.M2Artifact])] = Vector.empty

    override def publishToRemote(
        uri: String,
        workspace: os.Path,
        username: String,
        password: String,
        artifacts: IterableOnce[RemoteM2Publisher.M2Artifact]
    ): RemoteM2Publisher.DeployResult = {
      remoteDeploys :+= (uri, artifacts.iterator.toVector)
      RemoteM2Publisher.DeployResult(Vector.empty, Vector.empty)
    }

    override def publishToLocal(
        publishTo: os.Path,
        workspace: os.Path,
        artifacts: IterableOnce[RemoteM2Publisher.M2Artifact]
    ): RemoteM2Publisher.DeployResult = {
      localDeploys :+= (publishTo, artifacts.iterator.toVector)
      RemoteM2Publisher.DeployResult(Vector.empty, Vector.empty)
    }
  }

  private def publishData(dir: os.Path, id: String, version: String): PublishData = {
    val jar = dir / s"$id-$version.jar"
    val pom = dir / s"$id-$version.pom"
    os.write.over(jar, "JAR")
    os.write.over(pom, "POM")
    PublishData(
      Artifact("com.example", id, version),
      Map(
        os.SubPath(jar.last) -> PathRef(jar),
        os.SubPath(pom.last) -> PathRef(pom)
      )
    )
  }

  private val releaseUri = "https://example.com/releases"
  private val snapshotUri = "https://example.com/snapshots"

  def tests: Tests = Tests {
    // Publishing every module in a single deploy operation is what makes Maven give all of them
    // the same SNAPSHOT timestamp.
    test("mavenPublishDatas deploys all modules in one operation per repository") {
      val dir = os.temp.dir()
      val worker = RecordingWorker()
      val snapshots =
        Seq(publishData(dir, "a", "1.0-SNAPSHOT"), publishData(dir, "b", "1.0-SNAPSHOT"))
      val releases = Seq(publishData(dir, "c", "1.0"), publishData(dir, "d", "1.0"))

      mavenPublishDatas(
        // Interleaved so we also cover the partitioning.
        publishDatas = Seq(snapshots(0), releases(0), snapshots(1), releases(1)),
        credentials = (username = "user", password = "pass"),
        releaseUri = releaseUri,
        snapshotUri = snapshotUri,
        taskDest = dir / "dest",
        log = DummyLogger,
        env = Map.empty,
        worker = worker
      )

      assert(worker.localDeploys.isEmpty)
      assert(worker.remoteDeploys.map(_._1) == Vector(releaseUri, snapshotUri))
      // 2 modules * (jar + pom) in each deploy operation
      assert(worker.remoteDeploys.map(_._2.size) == Vector(4, 4))
    }

    test("mavenPublishDatas skips repositories with nothing to publish") {
      val dir = os.temp.dir()
      val worker = RecordingWorker()

      mavenPublishDatas(
        publishDatas = Seq(publishData(dir, "a", "1.0-SNAPSHOT")),
        credentials = (username = "user", password = "pass"),
        releaseUri = releaseUri,
        snapshotUri = snapshotUri,
        taskDest = dir / "dest",
        log = DummyLogger,
        env = Map.empty,
        worker = worker
      )

      assert(worker.remoteDeploys.map(_._1) == Vector(snapshotUri))
    }
  }
}
