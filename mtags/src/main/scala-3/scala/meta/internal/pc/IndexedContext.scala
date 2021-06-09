package scala.meta.internal.pc

import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.pc.IndexedContext.Result

import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.core.Flags._
import dotty.tools.dotc.core.Names._
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.core.Types._
import dotty.tools.dotc.typer.ImportInfo

sealed trait IndexedContext {
  given ctx: Context
  def scopeSymbols: List[Symbol]
  def rename(name: SimpleName): Option[String]
  def outer: IndexedContext

  def findSymbol(name: String): Option[List[Symbol]]

  final def findSymbol(name: Name): Option[List[Symbol]] =
    findSymbol(name.decoded)

  final def lookupSym(sym: Symbol): Result =
    findSymbol(sym.decodedName) match {
      case Some(symbols) if symbols.exists(_ == sym) =>
        Result.InScope
      case Some(_) => Result.Conflict
      case None => Result.Missing
    }

  final def hasRename(sym: Symbol, as: String): Boolean =
    rename(sym.name.toSimpleName) match {
      case Some(v) => v == as
      case None => false
    }

  final def isEmpty: Boolean = this match {
    case IndexedContext.Empty => true
    case _ => false
  }

  final def importContext: IndexedContext = {
    this match {
      case IndexedContext.Empty => this
      case _ if ctx.owner.is(Package) => this
      case _ => outer.importContext
    }
  }

}
object IndexedContext {

  def apply(ctx: Context): IndexedContext =
    ctx match {
      case null => Empty
      case NoContext => Empty
      case _ => LazyWrapper(ctx)
    }

  case object Empty extends IndexedContext {
    given ctx: Context = NoContext
    def findSymbol(name: String): Option[List[Symbol]] = None
    def symbolByName(name: String): Option[List[Symbol]] = None
    def scopeSymbols: List[Symbol] = List.empty
    def rename(name: SimpleName): Option[String] = None
    def outer: IndexedContext = this
  }

  class LazyWrapper(underlying: Context) extends IndexedContext {
    given ctx: Context = underlying
    val outer: IndexedContext = IndexedContext(underlying.outer)
    private lazy val names: Names = extractNames(underlying)

    def findSymbol(name: String): Option[List[Symbol]] = {
      symbolByName(name).orElse(outer.findSymbol(name))
    }

    def symbolByName(name: String): Option[List[Symbol]] =
      names.symbols.get(name).map(_.toList)

    def scopeSymbols: List[Symbol] =
      names.symbols.values.toList.flatten ++ outers.flatMap(_.scopeSymbols)

    def rename(name: SimpleName): Option[String] = {
      names.renames
        .get(name)
        .orElse(outer.rename(name))
    }

    private def outers: List[IndexedContext] = {
      val builder = List.newBuilder[IndexedContext]
      var curr = outer
      while (!curr.isEmpty) {
        builder += curr
        curr = curr.outer
      }
      builder.result
    }

  }

  enum Result {
    case InScope, Conflict, Missing
    def exists: Boolean = this match {
      case InScope | Conflict => true
      case Missing => false
    }
  }

  private case class Names(
      symbols: Map[String, List[Symbol]],
      renames: Map[SimpleName, String]
  )

  private def extractNames(ctx: Context): Names = {

    def accessibleSymbols(site: Type, tpe: Type)(using
        Context
    ): List[Symbol] = {
      tpe.decls.toList.filter(sym =>
        sym.isAccessibleFrom(site, superAccess = false)
      )
    }

    def accesibleMembers(site: Type)(using Context): List[Symbol] =
      site.allMembers
        .filter(denot =>
          denot.symbol.isAccessibleFrom(site, superAccess = false)
        )
        .map(_.symbol)
        .toList

    def allAccessibleSymbols(
        tpe: Type,
        filter: Symbol => Boolean = _ => true
    )(using Context): List[Symbol] = {
      val initial = accessibleSymbols(tpe, tpe).filter(filter)
      val fromPackageObjects =
        initial
          .filter(_.isPackageObject)
          .flatMap(sym => accessibleSymbols(tpe, sym.thisType))
      initial ++ fromPackageObjects
    }

    def fromImport(site: Type, name: Name)(using Context): List[Symbol] = {
      List(site.member(name.toTypeName), site.member(name.toTermName))
        .flatMap(_.alternatives)
        .map(_.symbol)
    }

    def fromImportInfo(
        imp: ImportInfo
    )(using Context): List[(Symbol, Option[TermName])] = {
      val excludedNames = imp.excluded.map(_.decoded)

      if (imp.isWildcardImport) {
        allAccessibleSymbols(
          imp.site,
          sym => !excludedNames.contains(sym.name.decoded)
        ).map((_, None))
      } else {
        imp.forwardMapping.toList.flatMap { (name, rename) =>
          val isRename = name != rename
          if (!isRename && !excludedNames.contains(name.decoded))
            fromImport(imp.site, name).map((_, None))
          else if (isRename)
            fromImport(imp.site, name).map((_, Some(rename)))
          else Nil
        }
      }
    }

    given Context = ctx
    val (symbols, renames) =
      if (ctx.isImportContext)
        val (syms, renames) =
          fromImportInfo(ctx.importInfo)
            .map((sym, rename) =>
              (sym, rename.map(r => sym.name.toSimpleName -> r.decoded))
            )
            .unzip
        (syms, renames.flatten.toMap)
      else if (ctx.owner.isClass) {
        val site = ctx.owner.thisType
        (accesibleMembers(site), Map.empty)
      } else if (ctx.scope != null) {
        (ctx.scope.toList, Map.empty)
      } else (List.empty, Map.empty)

    val initial = Map.empty[String, List[Symbol]]
    val values =
      symbols.foldLeft(initial) { (acc, sym) =>
        val name = sym.decodedName
        val syms = acc.getOrElse(name, List.empty)
        acc.updated(name, sym :: syms)
      }
    Names(values, renames)
  }
}
