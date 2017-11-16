//acyclic
package acyclic.plugin
import acyclic.file
import scala.tools.nsc.Global
object DependencyExtraction{
  def apply(global: Global)(unit: global.CompilationUnit): Seq[(global.Symbol, global.Tree)] = {
    import global._

    class CollectTypeTraverser[T](pf: PartialFunction[Type, T]) extends TypeTraverser {
      var collected: List[T] = Nil
      def traverse(tpe: Type): Unit = {
        if (pf.isDefinedAt(tpe))
          collected = pf(tpe) :: collected
        mapOver(tpe)
      }
    }

    class ExtractDependenciesTraverser extends Traverser {
      protected val depBuf = collection.mutable.ArrayBuffer.empty[(Symbol, Tree)]
      protected def addDependency(sym: Symbol, tree: Tree): Unit = depBuf += ((sym, tree))
      def dependencies: collection.immutable.Set[(Symbol, Tree)] = {
        // convert to immutable set and remove NoSymbol if we have one
        depBuf.toSet
      }

    }

    class ExtractDependenciesByMemberRefTraverser extends ExtractDependenciesTraverser {
      override def traverse(tree: Tree): Unit = {
        tree match {
          case i @ Import(expr, selectors) =>

            selectors.foreach {
              case ImportSelector(nme.WILDCARD, _, null, _) =>
              // in case of wildcard import we do not rely on any particular name being defined
              // on `expr`; all symbols that are being used will get caught through selections
              case ImportSelector(name: Name, _, _, _) =>
                def lookupImported(name: Name) = expr.symbol.info.member(name)
                // importing a name means importing both a term and a type (if they exist)
                addDependency(lookupImported(name.toTermName), tree)
                addDependency(lookupImported(name.toTypeName), tree)
            }
          case select: Select =>
            addDependency(select.symbol, tree)
          /*
           * Idents are used in number of situations:
           *  - to refer to local variable
           *  - to refer to a top-level package (other packages are nested selections)
           *  - to refer to a term defined in the same package as an enclosing class;
           *    this looks fishy, see this thread:
           *    https://groups.google.com/d/topic/scala-internals/Ms9WUAtokLo/discussion
           */
          case ident: Ident =>
            addDependency(ident.symbol, tree)
          case typeTree: TypeTree =>
            val typeSymbolCollector = new CollectTypeTraverser({
              case tpe if !tpe.typeSymbol.isPackage => tpe.typeSymbol
            })
            typeSymbolCollector.traverse(typeTree.tpe)
            val deps = typeSymbolCollector.collected.toSet
            deps.foreach(addDependency(_, tree))
          case Template(parents, self, body) =>
            traverseTrees(body)
          case other => ()
        }
        super.traverse(tree)
      }
    }

    def byMembers(): collection.immutable.Set[(Symbol, Tree)] = {
      val traverser = new ExtractDependenciesByMemberRefTraverser
      if (!unit.isJava)
        traverser.traverse(unit.body)
      traverser.dependencies
    }


    class ExtractDependenciesByInheritanceTraverser extends ExtractDependenciesTraverser {
      override def traverse(tree: Tree): Unit = tree match {
        case Template(parents, self, body) =>
          // we are using typeSymbol and not typeSymbolDirect because we want
          // type aliases to be expanded
          val parentTypeSymbols = parents.map(parent => parent.tpe.typeSymbol).toSet
          debuglog("Parent type symbols for " + tree.pos + ": " + parentTypeSymbols.map(_.fullName))
          parentTypeSymbols.foreach(addDependency(_, tree))
          traverseTrees(body)
        case tree => super.traverse(tree)
      }
    }

    def byInheritence(): collection.immutable.Set[(Symbol, Tree)] = {
      val traverser = new ExtractDependenciesByInheritanceTraverser
      if (!unit.isJava)
        traverser.traverse(unit.body)
      traverser.dependencies
    }

    (byMembers() | byInheritence()).toSeq
  }
}
