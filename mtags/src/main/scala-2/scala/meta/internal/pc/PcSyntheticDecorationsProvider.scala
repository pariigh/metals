package scala.meta.internal.pc

import scala.meta.pc.SyntheticDecoration
import scala.meta.pc.SyntheticDecorationsParams

final class PcSyntheticDecorationsProvider(
    protected val compiler: MetalsGlobal, // compiler
    val params: SyntheticDecorationsParams
) {
  import compiler._
  val unit: RichCompilationUnit = addCompilationUnit(
    code = params.text(),
    filename = params.uri().toString(),
    cursor = None
  )
  lazy val text = unit.source.content
  typeCheck(unit)

  def tpdTree = unit.lastBody

  def provide(): List[SyntheticDecoration] =
    traverse(Synthetics.empty, tpdTree).decorations

  def collectDecorations(
      tree: Tree,
      decorations: Synthetics
  ): Synthetics =
    tree match {
      case ImplicitConversion(name, pos) if params.implicitConversions() =>
        val adjusted = adjust(pos)
        decorations
          .add(
            Decoration(
              adjusted.focusStart.toLsp,
              name + "(",
              DecorationKind.ImplicitConversion
            )
          )
          .add(
            Decoration(
              adjusted.focusEnd.toLsp,
              ")",
              DecorationKind.ImplicitConversion
            )
          )
      case ImplicitParameters(names, pos, allImplicit)
          if params.implicitParameters() =>
        val label =
          if (allImplicit) names.mkString("(", ", ", ")")
          else names.mkString(", ", ", ", "")
        val adjusted = adjust(pos)
        decorations.add(
          Decoration(
            adjusted.focusEnd.toLsp,
            label,
            DecorationKind.ImplicitParameter
          )
        )
      case TypeParameters(types, pos) if params.typeParameters() =>
        val label = types.map(toLabel(_, pos)).mkString("[", ", ", "]")
        val adjusted = adjust(pos)
        decorations.add(
          Decoration(
            adjusted.focusEnd.toLsp,
            label,
            DecorationKind.TypeParameter
          )
        )
      case InferredType(tpe, pos) if params.inferredTypes() =>
        val label = toLabel(tpe, pos)
        val adjusted = adjust(pos)
        if (decorations.containsDef(adjusted.start)) decorations
        else
          decorations.add(
            Decoration(
              adjusted.focusEnd.toLsp,
              ": " + label,
              DecorationKind.InferredType
            ),
            adjusted.start
          )
      case _ => decorations
    }

  def traverse(
      acc: Synthetics,
      tree: Tree
  ): Synthetics = {
    val decorations = collectDecorations(tree, acc)
    tree.children.foldLeft(decorations)(traverse(_, _))
  }

  private def adjust(
      pos: Position
  ): Position = {
    if (pos.isOffset) pos
    else {
      val isOldNameBackticked = text(pos.start) == '`' &&
        (text(pos.end - 1) != '`' || pos.start == (pos.end - 1)) &&
        text(pos.end + 1) == '`'
      if (isOldNameBackticked) // pos
        pos.withEnd(pos.end + 2)
      else pos
    }
  }

  object ImplicitConversion {
    def unapply(tree: Tree): Option[(String, Position)] = tree match {
      case Apply(fun, args) if isImplicitConversion(fun) =>
        val lastArgPos = args.lastOption.fold(fun.pos)(_.pos)
        Some((fun.symbol.decodedName, lastArgPos))
      case _ => None
    }
    private def isImplicitConversion(fun: Tree) =
      fun.pos.isOffset && fun.symbol != null && fun.symbol.isImplicit
  }
  object ImplicitParameters {
    def unapply(tree: Tree): Option[(List[String], Position, Boolean)] =
      tree match {
        case Apply(_, args) if args.exists(isSyntheticArg) =>
          val (implicitArgs, providedArgs) = args.partition(isSyntheticArg)
          val allImplicit = providedArgs.isEmpty
          val pos = providedArgs.lastOption.fold(tree.pos)(_.pos)
          Some(implicitArgs.map(_.symbol.decodedName), pos, allImplicit)
        case _ => None
      }
    def isSyntheticArg(arg: Tree): Boolean =
      arg.pos.isOffset && arg.symbol != null && arg.symbol.isImplicit
  }

  object TypeParameters {
    def unapply(tree: Tree): Option[(List[Type], Position)] = tree match {
      case TypeApply(sel: Select, _)
          if isForComprehensionMethod(sel) || syntheticTupleApply(sel) =>
        None
      case TypeApply(fun, args)
          if args.exists(_.pos.isOffset) && tree.pos.isRange =>
        val pos = fun.pos
        Some(args.map(_.tpe.widen.finalResultType), pos)
      case _ => None
    }
  }

  object InferredType {
    def unapply(tree: Tree): Option[(Type, Position)] = tree match {
      case vd @ ValDef(_, _, tpt, _)
          if hasMissingTypeAnnot(vd, tpt) &&
            !primaryConstructorParam(vd.symbol) &&
            isNotInUnapply(vd) &&
            !isCompilerGeneratedSymbol(vd.symbol) =>
        Some(vd.symbol.tpe.widen.finalResultType, vd.namePosition)
      case dd @ DefDef(_, _, _, _, tpt, _)
          if hasMissingTypeAnnot(dd, tpt) &&
            !dd.symbol.isConstructor &&
            !dd.symbol.isMutable =>
        Some(dd.symbol.tpe.widen.finalResultType, findTpePos(dd))
      case bb @ Bind(name, Ident(nme.WILDCARD)) if name != nme.WILDCARD =>
        Some(bb.symbol.tpe.widen.finalResultType, bb.namePosition)
      case _ => None
    }
    private def hasMissingTypeAnnot(tree: MemberDef, tpt: Tree) =
      tree.pos.isRange && tree.namePosition.isRange && tpt.pos.isOffset

    private def primaryConstructorParam(sym: Symbol) =
      sym.safeOwner.isPrimaryConstructor

    private def findTpePos(dd: DefDef) = {
      if (dd.rhs.isEmpty) dd.pos
      else {
        val tpeIdx = text.lastIndexWhere(
          c => !c.isWhitespace && c != '=' && c != '{',
          dd.rhs.pos.start - 1
        )
        dd.pos.withEnd(tpeIdx + 1)
      }
    }
    private def isCompilerGeneratedSymbol(sym: Symbol) =
      sym.decodedName.matches("x\\$\\d+")

    private def isNotInUnapply(vd: ValDef) =
      !vd.rhs.pos.isRange || vd.rhs.pos.start > vd.namePosition.end
  }

  private val forCompMethods =
    Set(nme.map, nme.flatMap, nme.withFilter, nme.foreach)

  // We don't want to collect synthethic `map`, `withFilter`, `foreach` and `flatMap` in for-comprenhensions
  private def isForComprehensionMethod(sel: Select): Boolean = {
    val syntheticName = sel.name match {
      case name: TermName => forCompMethods(name)
      case _ => false
    }
    val wrongSpan = sel.qualifier.pos.includes(sel.namePosition.focusStart)
    syntheticName && wrongSpan
  }

  private def syntheticTupleApply(sel: Select): Boolean = {
    if (compiler.definitions.isTupleType(sel.tpe.finalResultType)) {
      sel match {
        case Select(tupleClass: Select, _)
            if tupleClass.pos.isRange &&
              tupleClass.name.startsWith("Tuple") =>
          val pos = tupleClass.pos
          !text.slice(pos.start, pos.end).mkString.startsWith("Tuple")
        case _ => true
      }
    } else false
  }
  private def toLabel(
      tpe: Type,
      pos: Position
  ): String = {
    val context: Context = doLocateImportContext(pos)
    val re: scala.collection.Map[Symbol, Name] = renamedSymbols(context)
    val history = new ShortenedNames(
      lookupSymbol = name =>
        context.lookupSymbol(name, sym => !sym.isStale) :: Nil,
      config = renameConfig,
      renames = re
    )
    metalsToLongString(tpe, history)
  }

  case class Synthetics(
      decorations: List[Decoration],
      definitions: Set[Int] = Set.empty
  ) {
    def containsDef(offset: Int): Boolean = definitions(offset)
    def add(decoration: Decoration, offset: Int): Synthetics =
      copy(
        decorations = decoration :: decorations,
        definitions = definitions + offset
      )
    def add(decoration: Decoration): Synthetics =
      copy(decorations = decoration :: decorations)
  }

  object Synthetics {
    def empty: Synthetics = Synthetics(Nil, Set.empty)
  }
}