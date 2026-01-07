# Mill Shading Support Implementation - Testing Guide

## Issue
GitHub Issue #3815: https://github.com/com-lihaoyi/mill/issues/3815

## Branch
`shading-support` on https://github.com/FusionBrah/mill

## What Was Implemented

**Core Files Created:**

| File | Purpose |
|------|---------|
| `libs/javalib/src/mill/javalib/ShadingModule.scala` | Core trait with `shadedMvnDeps`, `shadeRelocations`, `shadedJar`, `shadedArtifacts` tasks. Overrides `runClasspath`, `localClasspath`, `upstreamIvyAssemblyClasspath` |
| `libs/javalib/src/mill/javalib/ShadingPublishModule.scala` | Publishing trait that filters shaded deps from `publishXmlDeps` |
| `libs/scalalib/src/mill/scalalib/ScalaModule.scala` | Added `ScalaShadingModule` and `ScalaShadingPublishModule` traits at end of file |
| `libs/javalib/test/src/mill/javalib/ShadingModuleTests.scala` | Unit tests |
| `example/javalib/dependencies/6-shading/build.mill` | Java example (shades Gson) |
| `example/scalalib/dependencies/6-shading/build.mill` | Scala example (shades fansi) |

**Documentation Updated:**
- `website/docs/modules/ROOT/pages/javalib/dependencies.adoc`
- `website/docs/modules/ROOT/pages/scalalib/dependencies.adoc`

---

## How to Test

```bash
# Clone the branch
git clone -b shading-support https://github.com/FusionBrah/mill.git
cd mill

# Run unit tests for shading
./mill libs.javalib.test.testOnly mill.javalib.ShadingModuleTests

# Run the Scala example test
./mill example.scalalib.dependencies.6-shading.packaged.daemon

# Run the Java example test
./mill example.javalib.dependencies.6-shading.packaged.daemon

# Compile libs to check for errors
./mill libs.javalib.compile
./mill libs.scalalib.compile
```

---

## Expected Test Results

**Unit Tests (`ShadingModuleTests`):**
- `shadedJar.containsRelocatedClasses` - Verifies shaded JAR has `shaded/gson/Gson.class`, NOT `com/google/gson/Gson.class`
- `shadedJar.emptyWhenNoShadedDeps` - Empty JAR when no shaded deps defined
- `shadedArtifacts.containsTransitiveDeps` - Resolves transitive deps of shaded libraries
- `localClasspath.includesShadedJar` - shadedJar is in localClasspath
- `runClasspath.excludesOriginalShadedDeps` - Original dep JARs removed from runClasspath
- `runClasspath.includesShadedJar` - shadedJar is in runClasspath
- `jar.includesShadedClasses` - Final JAR contains relocated classes

**Example Tests:**
- Should compile and run successfully
- Output JAR should contain relocated classes (check with `unzip -l`)

---

## Architecture Overview

```
ShadingModule extends JavaModule
├── shadedMvnDeps: T[Seq[Dep]]              // Which deps to shade
├── shadeRelocations: T[Seq[(String,String)]] // (from, to) patterns
├── resolvedShadedDeps: T[Seq[PathRef]]     // Resolved JAR paths
├── shadedArtifacts: T[Set[Artifact]]       // For filtering in publish
├── shadedJar: T[PathRef]                   // The shaded output JAR
└── overrides: runClasspath, localClasspath, upstreamIvyAssemblyClasspath

ShadingPublishModule extends ShadingModule with PublishModule
└── override publishXmlDeps                 // Filters shaded deps from POM
```

---

## If Tests Fail

1. **Compilation errors in ShadingModule.scala**: Check imports - uses `mill.api.Task`, `mill.javalib.publish.Artifact`, etc.

2. **shadedJar fails**: The `Assembly.create()` call or `AssemblyModule.jarjarabramsWorker()` may have API changes - check `AssemblyModule.scala` for current signatures

3. **shadedArtifacts fails**: The `defaultResolver().resolution()` API may differ - check how other modules use Coursier resolution

4. **Publishing test fails**: Verify `publishXmlDeps` return type matches `Task[Seq[Dependency]]`

---

## Key Implementation Details

- Uses existing `Assembly.Rule.Relocate` and `jarjarabramsWorker()` for bytecode transformation
- Original deps used at compile time, shaded JAR used at runtime
- `shadedArtifacts` tracks all transitive deps for accurate POM filtering
- Empty shadedJar created when no `shadedMvnDeps` defined (graceful no-op)

---

## Plan File Reference

The full implementation plan is at: `~/.claude/plans/distributed-dazzling-moth.md`
