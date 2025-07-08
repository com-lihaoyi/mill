package mill

/**
 * Experimental toolchain for building Javascript and Typescript apps using Mill's
 * [[TypeScriptModule]], including support for React.js apps with  [[ReactScriptsModule]]
 */
package object javascriptlib {
  // These types are commonly used in javascript modules. Export them to make using
  // them possible without an import.

  export mill.javalib.publish.License

  export TypeScriptModule.PackageJson
}
