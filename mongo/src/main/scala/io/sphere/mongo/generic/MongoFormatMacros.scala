package io.sphere.mongo.generic

import io.sphere.mongo.format.MongoFormat

import scala.reflect.macros.blackbox

/**
  * copy/paste from https://github.com/sphereio/sphere-scala-libs/blob/master/json/src/main/scala/generic/JSONMacros.scala,
  * adapted to `MongoFormat`.
  */
private[generic] object MongoFormatMacros {
  private def collectKnownSubtypes(c: blackbox.Context)(s: c.universe.Symbol): Set[c.universe.Symbol] = {
    if (s.isModule || s.isModuleClass) Set(s)
    else if (s.isClass) {
      val cs = s.asClass
      if (cs.isCaseClass) Set(cs)
      else if ((cs.isTrait || cs.isAbstract) && cs.isSealed)
        cs.knownDirectSubclasses.flatMap(collectKnownSubtypes(c)(_))
      else Set.empty
    } else Set.empty
  }

  def mongoFormatProductApply(c: blackbox.Context)(tpe: c.universe.Type, classSym: c.universe.ClassSymbol): c.universe.Tree = {
    import c.universe._

    val symbol = tpe.typeSymbol
    val argList = classSym.toType.member(termNames.CONSTRUCTOR).asMethod.paramLists.head

    val (argDefs, args) = (for ((a, i) <- argList.zipWithIndex) yield {
      val argType = classSym.toType.member(a.name).typeSignatureIn(tpe)
      val argTree = ValDef(Modifiers(Flag.PARAM), TermName("x" + i), TypeTree(argType), EmptyTree)
      (argTree, Ident(TermName("x" + i)))
    }).unzip

    if (classSym.isCaseClass && !classSym.isModuleClass) {
      val applyBlock = Block(List(), Function(
        argDefs,
        Apply(Select(Ident(classSym.companion), TermName("apply")), args)
      ))
      Apply(
        Select(
          reify(io.sphere.mongo.generic.`package`).tree,
          TermName("mongoProduct")
        ),
        List(
          if (argList.size > 1)
            Select(applyBlock, TermName("curried"))
          else
            applyBlock
        )
      )
    } else if (classSym.isCaseClass && classSym.isModuleClass) {
      Apply(
        Select(
          reify(io.sphere.mongo.generic.`package`).tree,
          TermName("mongoProduct0")
        ),
        List(Ident(classSym.name.toTermName))
      )
    } else if (classSym.isModuleClass) {
      Apply(
        Select(
          reify(io.sphere.mongo.generic.`package`).tree,
          TermName("mongoSingleton")
        ),
        List(Ident(classSym.name.toTermName))
      )
    } else c.abort(c.enclosingPosition, "Not a case class or (case) object")
  }


  def deriveMongoFormat_impl[A: c.WeakTypeTag](c: blackbox.Context): c.Expr[MongoFormat[A]] = {
    import c.universe._

    val tpe = weakTypeOf[A]
    val symbol = tpe.typeSymbol

    if (tpe <:< weakTypeOf[Enumeration#Value]) {
      val TypeRef(pre, _, _) = tpe
      c.Expr[MongoFormat[A]](Apply(
        Select(
          reify(io.sphere.mongo.generic.`package`).tree,
          TermName("mongoEnum")
        ),
        List(Ident(pre.typeSymbol.name.toTermName))
      ))
    } else if (symbol.isClass && (symbol.asClass.isCaseClass || symbol.asClass.isModuleClass))
    // product type or singleton
      c.Expr[MongoFormat[A]](mongoFormatProductApply(c)(tpe, symbol.asClass))
    else {
      // sum type
      if (!symbol.isClass) c.abort(
        c.enclosingPosition,
        "Can only enumerate values of a sealed trait or class."
      ) else if (!symbol.asClass.isSealed) c.abort(
        c.enclosingPosition,
        "Can only enumerate values of a sealed trait or class."
      ) else {
        val subtypes = collectKnownSubtypes(c)(symbol)
        val idents = Ident(symbol.name) :: subtypes.map { s =>
          if (s.isModuleClass) New(TypeTree(s.asClass.toType)) else Ident(s.name)
        }.toList

        if (idents.size == 1)
          c.abort(c.enclosingPosition, "Subtypes not found.")
        else if (idents.size < 3)
          c.abort(c.enclosingPosition, "At least 2 subtypes in type switch required.")
        else {
          val instanceDefs = subtypes.zipWithIndex.collect {
            case (symbol, i) if symbol.isClass && symbol.asClass.isCaseClass =>
              if (symbol.asClass.typeParams.nonEmpty) {
                c.abort(c.enclosingPosition, "Types with type parameters cannot (yet) be derived as part of a sum type")
              } else {
                ValDef(
                  Modifiers(Flag.IMPLICIT),
                  TermName("mongo" + i),
                  AppliedTypeTree(
                    Ident(TypeName("MongoFormat")),
                    List(Ident(symbol))
                  ),
                  mongoFormatProductApply(c)(tpe, symbol.asClass)
                )
              }
          }.toList

          c.Expr[MongoFormat[A]](
            Block(
              instanceDefs,
              Apply(
                TypeApply(
                  Select(
                    reify(io.sphere.mongo.generic.`package`).tree,
                    TermName("mongoTypeSwitch")
                  ),
                  idents
                ),
                List(reify(Nil).tree)
              )
            )
          )
        }
      }
    }
  }
}
