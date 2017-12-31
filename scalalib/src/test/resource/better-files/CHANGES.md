better-files follows the following `MAJOR.MINOR.PATCH` release conventions:
- **Changes in `PATCH` version**: 
    - Minor functionality changes (usually bug fixes)
    - No breaking public API changes
    - New APIs might be added
- **Change in `MINOR` version**:
    - In addition to `PATCH` changes
    - Minor API shape changes e.g. renaming, deprecations 
    - Trivial to modify code to address compilation issues
- **Change in `MAJOR` version**:
    - In addition to `MINOR` changes
    - Significant structural and API changes
    
-----------

## v4.0.0
* [Issue #129](https://github.com/pathikrit/better-files/issues/129): JSR-203 and JimFS compatibility
* [Issue #88](https://github.com/pathikrit/better-files/issues/88): Strongly typed relative and absolute path APIs
* [Issue #122](https://github.com/pathikrit/better-files/issues/122): Scala Platform Release - Support for Scala 2.13 and 2.11
* Move Scanner to own module that depends on cats/shapeless
* Remove implicit options from all APIs

## v3.2.1
* [Issue #193](https://github.com/pathikrit/better-files/issues/193): Handle fast changing directory watching on Windows
* [Issue #195](https://github.com/pathikrit/better-files/issues/195): Do not swallow `FileAlreadyExistsException` when creating directory or file
* [Add](https://github.com/pathikrit/better-files/commit/00f27867ebd0cddec1ace7835dcc2375869fb3ae) method to check verified file existence (or non-existence)
* [Issue #198](https://github.com/pathikrit/better-files/issues/198): `InputStreamOps#asString` doesn't close the stream on exception
* [PR #199](https://github.com/pathikrit/better-files/pull/199): Utils for Object I/O

## v3.2.0
* [Rename](https://github.com/pathikrit/better-files/commit/ec34a6f843fec941b51bdddafc2e07e5bc0e1cbb) PosixFilePermissions.OTHERS* APIs
* [Issue #186](https://github.com/pathikrit/better-files/issues/186): Splitter based Scanner
* [Issue #173](https://github.com/pathikrit/better-files/issues/173): Better ARM handling of fatal errors
* [Issue #182](https://github.com/pathikrit/better-files/issues/182): Move and Copy *into* directory utils
* [Issue #189](https://github.com/pathikrit/better-files/issues/189): Util to read String from an InputStream
* [Issue #187](https://github.com/pathikrit/better-files/issues/187): Readers for `java.time.*` and `java.sql.*`
* [Restore File.usingTemp](https://github.com/pathikrit/better-files/commit/35184a642245db3d1e41fc02c7bfbec0b19a43bb) first introduced in [7c60ca](https://github.com/pathikrit/better-files/commit/d3522e8da63b55c7d3fa14cc9b0b76acd57c60ca)
* [Fix](https://github.com/pathikrit/better-files/pull/184) bug in appendBytes

## v3.1.0
* [Issue #140](https://github.com/pathikrit/better-files/issues/140): Batch up events for file monitoring
* [Issue #136](https://github.com/pathikrit/better-files/issues/136): Use execution contexts for file monitoring
* [Issue #152](https://github.com/pathikrit/better-files/issues/152): Streamed unzipping
* [Issue #150](https://github.com/pathikrit/better-files/issues/150): `ManagedResource[File]` for temp files
* [Issue #126](https://github.com/pathikrit/better-files/pull/159): New Typeclassed approach to ARM
* [Issue #160](https://github.com/pathikrit/better-files/issues/160): Ability to convert Reader/Writer to Input/Output streams
* [Issue #77](https://github.com/pathikrit/better-files/issues/77): Better UNIX-y behaviour for `cp` and `mv` DSL utils
* [Issue #169](https://github.com/pathikrit/better-files/issues/169): Support for symbols in file DSL
* [Issue #171](https://github.com/pathikrit/better-files/issues/171): Handle `createDirectories()` on symlinks to existing directories

## v3.0.0
* [Issue #9](https://github.com/pathikrit/better-files/issues/9): File resource utils
* [Issue #114](https://github.com/pathikrit/better-files/issues/114): Glob with automatic path
* [Issue #107](https://github.com/pathikrit/better-files/issues/107): Handle Byte-order markers
* [PR #113](https://github.com/pathikrit/better-files/pull/113): File anchor util
* [Issue #105](https://github.com/pathikrit/better-files/issues/105): Remove dependency on scala.io
* [File.usingTemp](https://github.com/pathikrit/better-files/commit/d3522e8da63b55c7d3fa14cc9b0b76acd57c60ca)
* [Optional symbolic operations](https://github.com/pathikrit/better-files/issues/102)
* [PR #100](https://github.com/pathikrit/better-files/pull/100): Fix issue in unzip of parents
* [PR #101](https://github.com/pathikrit/better-files/pull/101): Removed File.Type
* [Issue #96](https://github.com/pathikrit/better-files/issues/96): Teeing outputstreams
* [File.testPermission](https://github.com/pathikrit/better-files/commit/7b175c582643790e4d2fd21552e47cc9c615dfbb)
* [File.nonEmpty](https://github.com/pathikrit/better-files/commit/18c9cd51b7b2e503ff4944050ac5119470869e6e)
* [Update metadata API](https://github.com/pathikrit/better-files/commit/c3d65951d80f09b813e158a9e3a1785c622353b3)
* [Issue #80](https://github.com/pathikrit/better-files/issues/80): Unzip filters
* [PR #107](https://github.com/pathikrit/better-files/pull/127): Java serialization utils

## v2.17.1
* [PR #99](https://github.com/pathikrit/better-files/pull/99): Release for Scala 2.12

## v2.17.0
* [PR #78](https://github.com/pathikrit/better-files/pull/78): Change `write(Array[Byte])` to `writeByteArray()`. Same for `append`
* [Issue #76](https://github.com/pathikrit/better-files/issues/76): Move `better.files.Read` typeclass to `better.files.Scanner.Read`
