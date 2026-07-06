/*
 * Original code copied from https://github.com/lefou/mill-kotlin
 * Original code published under the Apache License Version 2
 * Original Copyright 2020-2024 Tobias Roeser
 */
package mill.kotlinlib.worker.impl

class KotlinWorkerImpl(
    private val classpathSnapshotCache: os.Path,
    private val classpathSnapshotCacheIsStable: Boolean
) extends KotlinWorkerImplBase(classpathSnapshotCache, classpathSnapshotCacheIsStable) {

  override protected def jvmBtApiCompiler(): BtApiCompiler = jvmBtApiCompiler0

  private lazy val jvmBtApiCompiler0: BtApiCompiler =
    JvmCompileBtApiImpl(classpathSnapshotCache)

}
