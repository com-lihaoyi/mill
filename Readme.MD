
# Mill

Your shiny new build tool!

## How to build and test

Run unit test suite:

```bash
sbt core/test
```

Build a standalone executable jar:

```bash
sbt scalaplugin/assembly
```

Now you can re-build this very same project using the build.sc file, e.g. re-run core unit tests

e.g.:
```bash
./scalaplugin/target/scala-2.12/mill-scalaplugin-assembly-0.1-SNAPSHOT.jar run Core.compile
./scalaplugin/target/scala-2.12/mill-scalaplugin-assembly-0.1-SNAPSHOT.jar run CoreTests.test
./scalaplugin/target/scala-2.12/mill-scalaplugin-assembly-0.1-SNAPSHOT.jar run ScalaPlugin.assembly
```

there is already a `watch` option that looks for changes on files, e.g.:
```bash
./scalaplugin/target/scala-2.12/mill-scalaplugin-assembly-0.1-SNAPSHOT.jar --watch run Core.compile
```

output will be generated into a the `./out` folder.

## build.sc

Into a `build.sc` file you can define separate `Module`s (e.g. `ScalaModule`).
Within each `Module` you can define 3 type of build:

 - `Target`: take no argument, output is cached and should be serializable; run from `bash` (e.g.: `def foo = T{...}`)
 - `Command`: take serializable arguments, output is not cached; run from `bash` (arguments with `scopt`) (e.g.: `def foo = T.command{...}`)
 - `Task`: take arguments, output is not cached; do not run from `bash` (e.g.: `def foo = T.task{...}` )  

## Troubleshooting

In case of troubles with caching and/or incremental compilation, you can always restart from scratch removing the `out` directory:

```bash
rm -rf out/
```
