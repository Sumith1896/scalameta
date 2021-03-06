package scala.meta
package semantic

import org.scalameta.adt._
import org.scalameta.annotations._
import org.scalameta.invariants._
import org.scalameta.unreachable
import scala.language.experimental.macros
import scala.{Seq => _}
import scala.annotation.compileTimeOnly
import scala.collection.immutable.Seq
import scala.reflect.{ClassTag, classTag}
import scala.meta.artifacts._
import scala.meta.prettyprinters._
import scala.meta.internal.{ast => impl} // necessary only to implement APIs, not to define them
import scala.meta.internal.{semantic => s} // necessary only to implement APIs, not to define them
import scala.meta.internal.{equality => e} // necessary only to implement APIs, not to define them
import scala.meta.internal.prettyprinters.Summary // necessary only to implement APIs, not to define them
import scala.meta.internal.ast.Helpers._ // necessary only to implement APIs, not to define them
import scala.reflect.runtime.{universe => ru} // necessary only for a very hacky approximation of hygiene

private[meta] trait Api {
  // ===========================
  // PART 1: PRETTYPRINTING
  // ===========================
  implicit def showSemantics[T <: Tree](implicit c: SemanticContext): Semantics[T] = scala.meta.internal.prettyprinters.TreeSemantics.apply[T](c)

  // ===========================
  // PART 2: EQUALITY
  // ===========================

  implicit class XtensionTypecheckingEquality[T1 <: Tree](tree1: T1) {
    @hosted def =:=[T2 <: Tree](tree2: T2)(implicit ev: e.AllowEquality[T1, T2]): Boolean = e.Typechecking.equals(tree1, tree2)
    @hosted def =!=[T2 <: Tree](tree2: T2)(implicit ev: e.AllowEquality[T1, T2]): Boolean = !e.Typechecking.equals(tree1, tree2)
  }

  implicit class XtensionNormalizingEquality[T1 <: Tree](tree1: T1) {
    @hosted def =~=[T2 <: Tree](tree2: T2)(implicit ev: e.AllowEquality[T1, T2]): Boolean = e.Normalizing.equals(tree1, tree2)
    // TODO: what would be the symbol to express negation of =~=?
  }

  // ===========================
  // PART 3: CONFIGURATION
  // ===========================

  @hosted def dialect: Dialect = implicitly[SemanticContext].dialect

  @hosted def domain: Domain = implicitly[SemanticContext].domain

  implicit class XtensionDomainLikeContext(c: SemanticContext) {
    def sources: Seq[Source] = c.domain.sources
    def resources: Seq[Resource] = c.domain.resources
  }

  // ===========================
  // PART 4: ATTRIBUTES
  // ===========================

  implicit class XtensionSemanticTermDesugar(tree: Term) {
    @hosted def desugar: Term = {
      val ttree = implicitly[SemanticContext].typecheck(tree).require[impl.Term]
      ttree.expansion match {
        case s.Expansion.Zero => unreachable
        case s.Expansion.Identity => ttree
        case s.Expansion.Desugaring(desugaring) => desugaring
      }
    }
  }

  implicit class XtensionSemanticTermTpe(tree: Term) {
    @hosted def tpe: Type = {
      val ttree = implicitly[SemanticContext].typecheck(tree).require[impl.Term]
      ttree.typing match {
        case s.Typing.Zero => unreachable
        case s.Typing.Recursive => impl.Type.Singleton(ttree.require[impl.Term.Ref]).setTypechecked
        case s.Typing.Nonrecursive(tpe) => tpe.require[impl.Type]
      }
    }
  }

  implicit class XtensionSemanticMemberTpe(tree: Member) {
    @hosted private def SeqRef: impl.Type.Name = {
      val sScala = s.Symbol.Global(s.Symbol.RootPackage, "scala", s.Signature.Term)
      val sCollection = s.Symbol.Global(sScala, "collection", s.Signature.Term)
      val sSeq = s.Symbol.Global(sCollection, "Seq", s.Signature.Type)
      impl.Type.Name("Seq").withAttrs(s.Denotation.Single(s.Prefix.Zero, sSeq)).setTypechecked
    }
    @hosted private def paramType(tree: impl.Term.Param): Type = {
      val ttree = implicitly[SemanticContext].typecheck(tree).require[impl.Term.Param]
      ttree.typing match {
        case s.Typing.Zero => unreachable
        case s.Typing.Recursive => unreachable
        case s.Typing.Nonrecursive(impl.Type.Arg.ByName(tpe)) => impl.Type.Apply(SeqRef, List(tpe)).setTypechecked
        case s.Typing.Nonrecursive(impl.Type.Arg.Repeated(tpe)) => tpe
        case s.Typing.Nonrecursive(tpe: impl.Type) => tpe
      }
    }
    @hosted private def methodType(tparams: Seq[Type.Param], paramss: Seq[Seq[Term.Param]], ret: Type): Type = {
      if (tparams.nonEmpty) {
        val monoret = methodType(Nil, paramss, ret.require[impl.Type]).require[impl.Type]
        impl.Type.Lambda(tparams.require[Seq[impl.Type.Param]], monoret).setTypechecked
      } else paramss.foldRight(ret.require[impl.Type])((params, acc) => {
        val paramtypes = params.map(p => paramType(p.require[impl.Term.Param]).require[impl.Type.Arg])
        impl.Type.Function(paramtypes, acc).setTypechecked
      })
    }
    @hosted private def ctorType(owner: Member, paramss: Seq[Seq[Term.Param]]): Type = {
      val tparams = owner.tparams
      val ret = {
        if (tparams.nonEmpty) impl.Type.Apply(owner.tpe.require[impl.Type], tparams.map(_.name.require[impl.Type.Name]))
        else owner.tpe
      }.setTypechecked
      methodType(tparams, paramss, ret)
    }
    @hosted def tpe: Type = tree.require[impl.Member] match {
      case tree: impl.Pat.Var.Term => tree.name.tpe
      case tree: impl.Pat.Var.Type => tree.name
      case tree: impl.Decl.Def => methodType(tree.tparams, tree.paramss, tree.decltpe)
      case tree: impl.Decl.Type => tree.name
      case tree: impl.Defn.Def => methodType(tree.tparams, tree.paramss, tree.decltpe.getOrElse(tree.body.tpe))
      case tree: impl.Defn.Macro => methodType(tree.tparams, tree.paramss, tree.decltpe)
      case tree: impl.Defn.Type => tree.name
      case tree: impl.Defn.Class => tree.name
      case tree: impl.Defn.Trait => tree.name
      case tree: impl.Defn.Object => impl.Type.Singleton(tree.name).setTypechecked
      case       impl.Pkg(name: impl.Term.Name, _) => impl.Type.Singleton(name).setTypechecked
      case       impl.Pkg(impl.Term.Select(_, name: impl.Term.Name), _) => impl.Type.Singleton(name).setTypechecked
      case tree: impl.Pkg.Object => impl.Type.Singleton(tree.name).setTypechecked
      case tree: impl.Term.Param if tree.parent.map(_.isInstanceOf[impl.Template]).getOrElse(false) => ??? // TODO: don't forget to intersect with the owner type
      case tree: impl.Term.Param => paramType(tree)
      case tree: impl.Type.Param => tree.name.require[Type.Name]
      case tree: impl.Ctor.Primary => ctorType(tree.owner.require[Member], tree.paramss)
      case tree: impl.Ctor.Secondary => ctorType(tree.owner.require[Member], tree.paramss)
    }
  }

  implicit class XtensionSemanticRefDefn(tree: Ref) {
    @hosted def defns: Seq[Member] = implicitly[SemanticContext].defns(tree)
    @hosted def defn: Member = {
      defns match {
        case Seq(single) => single
        case Seq(_, _*) => throw new SemanticException(s"multiple definitions found for ${showSummary(tree)}")
        case Seq() => unreachable(debug(tree, tree.show[Structure]))
      }
    }
  }

  implicit class XtensionSemanticTermRefDefn(tree: Term.Ref) {
    @hosted def defns: Seq[Member.Term] = (tree: Ref).defns.map(_.require[Member.Term])
    @hosted def defn: Member.Term = (tree: Ref).defn.require[Member.Term]
  }

  // NOTE: the types here are intentionally just Member, not Member.Type
  // because Type.Refs can refer to both type members (obviously) and term members (singleton types)
  implicit class XtensionSemanticTypeRefDefn(tree: Type.Ref) {
    @hosted def defns: Seq[Member] = (tree: Ref).defns
    @hosted def defn: Member = (tree: Ref).defn
  }

  // ===========================
  // PART 5: TYPES
  // ===========================

  implicit class XtensionSemanticType(tree: Type) {
    @hosted def <:<(other: Type): Boolean = implicitly[SemanticContext].isSubtype(tree, other)
    @hosted def widen: Type = implicitly[SemanticContext].widen(tree)
    @hosted def dealias: Type = implicitly[SemanticContext].dealias(tree)
    @hosted def companion: Type.Ref = ???
    @hosted def supertypes: Seq[Type] = implicitly[SemanticContext].supertypes(tree)
  }

  @hosted def lub(tpes: Type*): Type = implicitly[SemanticContext].lub(tpes.toList)
  @hosted def glb(tpes: Type*): Type = implicitly[SemanticContext].glb(tpes.toList)

  // ===========================
  // PART 6: MEMBERS
  // ===========================

  trait XtensionSemanticMemberLike {
    @hosted protected def tree: Member
    // TODO: An alternative design for typeSignatureIn that is very much worth exploring
    // consists in lazy recalculation of signatures produced by Scope.members.
    // Much like we plan to remember lexical contexts, we could also remember type parameters to be instantiated.
    // For example, `t"List[Int]".defs("head")` would give us `def head: A = ...`,
    // with A carrying information about the fact that it should be substituted for Int.
    // My only immediate concern here is what to do with `show[Syntax]`, but that we can figure out.
    // Even though this design looks more principled and arguably more elegant that eager recalculation,
    // I ended up not going for it, because it is much less straightforward implementation-wise,
    // and any time savings are worth very much at this stage of the project.
    @hosted def source: Member = tree.name match {
      case name: impl.Name.Anonymous => name.defn
      case name: impl.Name.Indeterminate => name.defn
      case name: impl.Term.Name => name.withAttrs(name.denot.stripPrefix, s.Typing.Zero).defn
      case name: impl.Type.Name => name.withAttrs(name.denot.stripPrefix).defn
      case name: impl.Ctor.Name => name.withAttrs(name.denot.stripPrefix, s.Typing.Zero).defn
    }
    @hosted def name: Name = tree.name
    @hosted def supermembers: Seq[Member] = implicitly[SemanticContext].supermembers(tree)
    @hosted def submembers: Seq[Member] = implicitly[SemanticContext].submembers(tree)
    @hosted def companion: Member = {
      val candidates = {
        if (tree.isClass || tree.isTrait) tree.owner.members.filter(m => m.isObject && m.name.toString == tree.name.toString)
        else if (tree.isObject) tree.owner.members.filter(m => (m.isClass || m.isTrait) && m.name.toString == tree.name.toString)
        else throw new SemanticException(s"can't have companions for ${showSummary(tree)}")
      }
      require(candidates.length < 2)
      candidates match {
        case Seq(companion) => companion
        case Seq() => throw new SemanticException(s"no companions for ${showSummary(tree)}")
        case _ => unreachable(debug(tree, tree.show[Structure]))
      }
    }
    @hosted def mods: Seq[Mod] = {
      def fieldMods(tree: impl.Pat.Var.Term): Seq[Mod] = {
        tree.firstNonPatParent match {
          case Some(parent: impl.Decl.Val) => parent.mods
          case Some(parent: impl.Decl.Var) => parent.mods
          case Some(parent: impl.Defn.Val) => parent.mods
          case Some(parent: impl.Defn.Var) => parent.mods
          case _ => Nil
        }
      }
      tree.require[impl.Member] match {
        case tree: impl.Pat.Var.Term => fieldMods(tree)
        case tree: impl.Pat.Var.Type => Nil
        case tree: impl.Decl.Def => tree.mods
        case tree: impl.Decl.Type => tree.mods
        case tree: impl.Defn.Def => tree.mods
        case tree: impl.Defn.Macro => tree.mods
        case tree: impl.Defn.Type => tree.mods
        case tree: impl.Defn.Class => tree.mods
        case tree: impl.Defn.Trait => tree.mods
        case tree: impl.Defn.Object => tree.mods
        case tree: impl.Pkg => Nil
        case tree: impl.Pkg.Object => tree.mods
        case tree: impl.Term.Param => tree.mods
        case tree: impl.Type.Param => tree.mods
        case tree: impl.Ctor.Primary => tree.mods
        case tree: impl.Ctor.Secondary => tree.mods
      }
    }
    @hosted def annots: Seq[Term] = tree.mods.collect{ case impl.Mod.Annot(ref) => ref }
    @hosted def isVal: Boolean = {
      val patVarTerm = Some(tree).collect{case tree: impl.Pat.Var.Term => tree}
      val relevantParent = patVarTerm.flatMap(_.firstNonPatParent)
      relevantParent.map(s => s.isInstanceOf[impl.Decl.Val] || s.isInstanceOf[impl.Defn.Val]).getOrElse(false)
    }
    @hosted def isVar: Boolean = {
      val patVarTerm = Some(tree).collect{case tree: impl.Pat.Var.Term => tree}
      val relevantParent = patVarTerm.flatMap(_.firstNonPatParent)
      relevantParent.map(s => s.isInstanceOf[impl.Decl.Var] || s.isInstanceOf[impl.Defn.Var]).getOrElse(false)
    }
    @hosted def isDef: Boolean = tree.isInstanceOf[impl.Decl.Def] || tree.isInstanceOf[impl.Defn.Def]
    @hosted def isCtor: Boolean = tree.isInstanceOf[impl.Ctor.Primary] || tree.isInstanceOf[impl.Ctor.Secondary]
    @hosted def isPrimaryCtor: Boolean = tree.isInstanceOf[impl.Ctor.Primary]
    @hosted def isMacro: Boolean = tree.isInstanceOf[impl.Defn.Macro]
    @hosted def isAbstractType: Boolean = tree.isInstanceOf[impl.Decl.Type]
    @hosted def isAliasType: Boolean = tree.isInstanceOf[impl.Defn.Type]
    @hosted def isClass: Boolean = tree.isInstanceOf[impl.Defn.Class]
    @hosted def isTrait: Boolean = tree.isInstanceOf[impl.Defn.Trait]
    @hosted def isObject: Boolean = tree.isInstanceOf[impl.Defn.Object]
    @hosted def isPackage: Boolean = tree.isInstanceOf[impl.Pkg]
    @hosted def isPackageObject: Boolean = tree.isInstanceOf[impl.Pkg.Object]
    @hosted def isPrivate: Boolean = tree.mods.exists(_.isInstanceOf[impl.Mod.Private])
    @hosted def isProtected: Boolean = tree.mods.exists(_.isInstanceOf[impl.Mod.Protected])
    @hosted def isPublic: Boolean = !tree.isPrivate && !tree.isProtected
    @hosted def accessBoundary: Option[Name.Qualifier] = tree.mods.collectFirst { case impl.Mod.Private(name) => name; case impl.Mod.Protected(name) => name }
    @hosted def isImplicit: Boolean = tree.mods.exists(_.isInstanceOf[impl.Mod.Implicit])
    @hosted def isFinal: Boolean = tree.mods.exists(_.isInstanceOf[impl.Mod.Final]) || tree.isObject
    @hosted def isSealed: Boolean = tree.mods.exists(_.isInstanceOf[impl.Mod.Sealed])
    @hosted def isOverride: Boolean = {
      def isSyntacticOverride = !isAbstract && tree.mods.exists(_.isInstanceOf[impl.Mod.Override])
      def isSemanticOverride = {
        def isEligible = isVal || isVar || isDef || isMacro || isAbstractType || isAliasType
        def overridesSomething = supermembers.nonEmpty
        isEligible && overridesSomething
      }
      isSyntacticOverride || isSemanticOverride
    }
    @hosted def isCase: Boolean = tree.mods.exists(_.isInstanceOf[impl.Mod.Case])
    @hosted def isAbstract: Boolean = {
      val isAbstractClass = !isAbstractOverride && tree.mods.exists(_.isInstanceOf[impl.Mod.Abstract])
      val isAbstractMember = tree.isInstanceOf[impl.Decl]
      isAbstractClass || isAbstractMember
    }
    @hosted def isCovariant: Boolean = tree.mods.exists(_.isInstanceOf[impl.Mod.Covariant])
    @hosted def isContravariant: Boolean = tree.mods.exists(_.isInstanceOf[impl.Mod.Contravariant])
    @hosted def isLazy: Boolean = tree.mods.exists(_.isInstanceOf[impl.Mod.Lazy])
    @hosted def isAbstractOverride: Boolean = (
      tree.mods.exists(_.isInstanceOf[impl.Mod.Abstract]) &&
      tree.mods.exists(_.isInstanceOf[impl.Mod.Override])
    )
    @hosted def isTermBind: Boolean = !tree.isVal && !tree.isVar && tree.isInstanceOf[impl.Pat.Var.Term]
    @hosted def isTypeBind: Boolean = tree.isInstanceOf[impl.Pat.Var.Type]
    @hosted def isTermParam: Boolean = tree.isInstanceOf[impl.Term.Param]
    @hosted def isTypeParam: Boolean = tree.isInstanceOf[impl.Type.Param]
    @hosted def isAnonymous: Boolean = {
      tree.require[impl.Member] match {
        case tree: impl.Term.Param => tree.name.isInstanceOf[impl.Name.Anonymous]
        case tree: impl.Type.Param => tree.name.isInstanceOf[impl.Name.Anonymous]
        case _ => false
      }
    }
    @hosted def isByNameParam: Boolean = tree match { case impl.Term.Param(_, _, Some(impl.Type.Arg.ByName(_)), _) => true; case _ => false }
    @hosted def isVarargParam: Boolean = tree match { case impl.Term.Param(_, _, Some(impl.Type.Arg.Repeated(_)), _) => true; case _ => false }
    @hosted def isValParam: Boolean = tree.mods.exists(_.isInstanceOf[impl.Mod.ValParam])
    @hosted def isVarParam: Boolean = tree.mods.exists(_.isInstanceOf[impl.Mod.VarParam])
  }

  implicit class XtensionSemanticMember(member: Member) extends XtensionSemanticMemberLike {
    @hosted protected def tree: Member = member
  }

  implicit class XtensionSemanticRefMemberLike(ref: Ref) extends XtensionSemanticMemberLike {
    @hosted protected def tree: Member = ref.defn
  }

  implicit class XtensionSemanticTermMember(tree: Member.Term) {
    @hosted def source: Member.Term = new XtensionSemanticMember(tree).source.require[Member.Term]
    @hosted def name: Term.Name = new XtensionSemanticMember(tree).name.require[Term.Name]
    @hosted def supermembers: Seq[Member.Term] = new XtensionSemanticMember(tree).supermembers.require[Seq[Member.Term]]
    @hosted def submembers: Seq[Member.Term] = new XtensionSemanticMember(tree).submembers.require[Seq[Member.Term]]
    @hosted def companion: Member.Type = new XtensionSemanticMember(tree).companion.require[Member.Type]
  }

  implicit class XtensionSemanticTermRefMemberLike(tree: Term.Ref) {
    @hosted def source: Member.Term = new XtensionSemanticRefMemberLike(tree).source.require[Member.Term]
    @hosted def name: Term.Name = new XtensionSemanticRefMemberLike(tree).name.require[Term.Name]
    @hosted def supermembers: Seq[Member.Term] = new XtensionSemanticRefMemberLike(tree).supermembers.require[Seq[Member.Term]]
    @hosted def submembers: Seq[Member.Term] = new XtensionSemanticRefMemberLike(tree).submembers.require[Seq[Member.Term]]
    @hosted def companion: Member.Type = new XtensionSemanticRefMemberLike(tree).companion.require[Member.Type]
  }

  implicit class XtensionSemanticTypeMember(tree: Member.Type) {
    @hosted def source: Member.Type = new XtensionSemanticMember(tree).source.require[Member.Type]
    @hosted def name: Type.Name = new XtensionSemanticMember(tree).name.require[Type.Name]
    @hosted def supermembers: Seq[Member.Type] = new XtensionSemanticMember(tree).supermembers.require[Seq[Member.Type]]
    @hosted def submembers: Seq[Member.Type] = new XtensionSemanticMember(tree).submembers.require[Seq[Member.Type]]
    @hosted def companion: Member.Term = new XtensionSemanticMember(tree).companion.require[Member.Term]
  }

  implicit class XtensionSemanticCtor(tree: Ctor) {
    @hosted def source: Ctor = new XtensionSemanticMember(tree).source.require[Ctor]
    @hosted def name: Ctor.Name = new XtensionSemanticMember(tree).name.require[Ctor.Name]
    @hosted def supermembers: Seq[Ctor] = new XtensionSemanticMember(tree).supermembers.require[Seq[Ctor]]
    @hosted def submembers: Seq[Ctor] = new XtensionSemanticMember(tree).submembers.require[Seq[Ctor]]
    @hosted def companion: Member = new XtensionSemanticMember(tree).companion.require[Member]
  }

  // NOTE: no additional methods here unlike in SemanticTermRefMemberLikeOps
  // because Type.Refs can refer to both type members (obviously) and term members (singleton types)
  implicit class XtensionSemanticTypeRefMemberLike(tree: Type.Ref)

  implicit class XtensionSemanticTermParam(tree: Term.Param) {
    @hosted def source: Term.Param = new XtensionSemanticMember(tree).name.require[Term.Param]
    @hosted def name: Term.Param.Name = new XtensionSemanticMember(tree).name.require[Term.Param.Name]
    @hosted def default: Option[Term] = tree.require[impl.Term.Param].default
    @hosted def field: Member.Term = tree.owner.owner.members(tree.name).require[Member.Term]
  }

  implicit class XtensionSemanticTypeParam(tree: Type.Param) {
    @hosted def source: Type.Param = new XtensionSemanticMember(tree).name.require[Type.Param]
    @hosted def name: Type.Param.Name = new XtensionSemanticMember(tree).name.require[Type.Param.Name]
    @hosted def contextBounds: Seq[Type] = tree.require[impl.Type.Param].cbounds
    @hosted def viewBounds: Seq[Type] = tree.require[impl.Type.Param].vbounds
    @hosted def lo: Type = tree.require[impl.Type.Param].lo
    @hosted def hi: Type = tree.require[impl.Type.Param].hi
  }

  private implicit class XtensionDenotationStripPrefix(denot: s.Denotation) {
    def stripPrefix = denot match {
      case s.Denotation.Zero => s.Denotation.Zero
      case denot: s.Denotation.Single => denot.copy(prefix = s.Prefix.Zero)
      case denot: s.Denotation.Multi => denot.copy(prefix = s.Prefix.Zero)
    }
  }

  // ===========================
  // PART 7: SCOPES
  // ===========================

  // TODO: so what I wanted to do with Scope.members is to have three overloads:
  // * () => Seq[Member]
  // * Name => Member
  // * T <: Member => T <: Member
  // unfortunately, if I try to introduce all the overloads, scalac compiler gets seriously confused
  // when I'm trying to call members for any kind of name
  // therefore, I'm essentially forced to use a type class here
  // another good idea would be to name these methods differently
  sealed trait XtensionMembersSignature[T, U]
  object XtensionMembersSignature {
    implicit def NameToMember[T <: Name]: XtensionMembersSignature[T, Member] = null
    implicit def MemberToMember[T <: Member]: XtensionMembersSignature[T, T] = null
  }

  trait XtensionSemanticScopeLike {
    @hosted protected def tree: Scope
    @hosted def owner: Scope = {
      def fromSyntax(tree: Tree): Option[Scope] = {
        tree.parent.flatMap(_ match {
          case scope: Scope => Some(scope)
          case other => fromSyntax(other)
        })
      }
      def fromPrefix(prefix: s.Prefix): Option[Member] = {
        // TODO: this should account for type arguments of the prefix!
        // TODO: also prefix types are probably more diverse than what's supported now
        prefix match {
          case s.Prefix.Type(ref: impl.Type.Ref) => Some(ref.defn)
          case s.Prefix.Type(impl.Type.Apply(tpe, _)) => fromPrefix(s.Prefix.Type(tpe))
          case s.Prefix.Type(impl.Type.ApplyInfix(_, tpe, _)) => fromPrefix(s.Prefix.Type(tpe))
          case _ => None
        }
      }
      tree.require[impl.Scope] match {
        case member: impl.Member => fromSyntax(member).orElse(fromPrefix(member.name.require[impl.Name].denot.prefix)).get
        case term: impl.Term => fromSyntax(term).get
        case pat: impl.Pat => fromSyntax(pat).get
        case cas: impl.Case => fromSyntax(cas).get
        case tpe: impl.Type.Ref => tpe.defn.owner
        case tpe: impl.Type => ???
        case _ => unreachable(debug(tree))
      }
    }
    @hosted private[meta] def deriveEvidences(tparam: Type.Param): Seq[Term.Param] = {
      def deriveEvidence(evidenceTpe: Type): Term.Param = {
        // TODO: it's almost a decent parameter except for the facts that:
        // 1) the tree doesn't have a parent or a denotation, and we'd have remember that it comes from tparam
        //    otherwise, tree.owner is going crash (luckily, i can't imagine this tree participating in other semantic calls)
        // 2) the name is anonymous, but it's not actually a correct way of modelling it,
        //    because the user can refer to that name via implicit search
        //    so far we strip off all desugarings and, hence, all inferred implicit arguments, so that's not a problem for us, but it will be
        // NOTE: potential solution would involve having the symbol of the parameter to be of a special, new kind
        // Symbol.Synthetic(origin: Symbol, generator: ???)
        impl.Term.Param(List(impl.Mod.Implicit()), impl.Name.Anonymous(), Some(evidenceTpe.require[impl.Type]), None).setTypechecked
      }
      def deriveViewEvidence(tpe: Type) = deriveEvidence(impl.Type.Function(List(tparam.name.require[impl.Type]), tpe.require[impl.Type]))
      def deriveContextEvidence(tpe: Type) = deriveEvidence(impl.Type.Apply(tpe.require[impl.Type], List(tparam.name.require[impl.Type])))
      tparam.viewBounds.map(deriveViewEvidence) ++ tparam.contextBounds.map(deriveContextEvidence)
    }
    @hosted private[meta] def mergeEvidences(paramss: Seq[Seq[Term.Param]], evidences: Seq[Term.Param]): Seq[Seq[Term.Param]] = {
      paramss match {
        case init :+ last if last.exists(_.isImplicit) => init :+ (last ++ evidences)
        case init :+ last => init :+ last :+ evidences
        case Nil => List(evidences)
      }
    }
    @hosted private[meta] def internalAll: Seq[Member] = {
      def membersOfStats(stats: Seq[impl.Tree]) = stats.collect{
        case member: Member => member
      }
      def membersOfEnumerator(enum: impl.Enumerator) = enum match {
        case impl.Enumerator.Generator(pat, _) => membersOfPat(pat)
        case impl.Enumerator.Val(pat, _) => membersOfPat(pat)
        case impl.Enumerator.Guard(_) => Nil
      }
      def membersOfPatType(ptpe: impl.Pat.Type): Seq[impl.Member] = ptpe match {
        case impl.Pat.Type.Wildcard() => Nil
        case ptpe @ impl.Pat.Var.Type(_) => List(ptpe)
        case impl.Type.Name(_) => Nil
        case impl.Type.Select(_, _) => Nil
        case impl.Pat.Type.Project(ptpe, _) => membersOfPatType(ptpe)
        case impl.Type.Singleton(_) => Nil
        case impl.Pat.Type.Apply(tpe, args) => membersOfPatType(tpe) ++ args.flatMap(membersOfPatType)
        case impl.Pat.Type.ApplyInfix(lhs, _, rhs) => membersOfPatType(lhs) ++ membersOfPatType(rhs)
        case impl.Pat.Type.Function(params, res) => params.flatMap(membersOfPatType) ++ membersOfPatType(res)
        case impl.Pat.Type.Tuple(elements) => elements.flatMap(membersOfPatType)
        case impl.Pat.Type.Compound(tpes, _) => tpes.flatMap(membersOfPatType)
        case impl.Pat.Type.Existential(tpe, _) => membersOfPatType(tpe)
        case impl.Pat.Type.Annotate(tpe, _) => membersOfPatType(tpe)
        case impl.Pat.Type.Lambda(_, tpe) => membersOfPatType(tpe)
        case impl.Pat.Type.Placeholder(_) => Nil
        case _: impl.Lit => Nil
      }
      def membersOfPat(pat: impl.Pat.Arg): Seq[impl.Member] = pat match {
        case impl.Pat.Wildcard() => Nil
        case pat @ impl.Pat.Var.Term(name) => List(pat)
        case impl.Pat.Bind(lhs, rhs) => membersOfPat(lhs) ++ membersOfPat(rhs)
        case impl.Pat.Alternative(lhs, rhs) => membersOfPat(lhs) ++ membersOfPat(rhs)
        case impl.Pat.Tuple(elements) => elements.flatMap(membersOfPat)
        case impl.Pat.Extract(_, _, elements) => elements.flatMap(membersOfPat)
        case impl.Pat.ExtractInfix(lhs, _, rhs) => membersOfPat(lhs) ++ rhs.flatMap(membersOfPat)
        case impl.Pat.Interpolate(_, _, args) => args.flatMap(membersOfPat)
        case impl.Pat.Typed(lhs, ptpe) => membersOfPat(lhs) ++ membersOfPatType(ptpe)
        case impl.Pat.Arg.SeqWildcard() => Nil
        case impl.Term.Name(_) => Nil
        case impl.Term.Select(_, _) => Nil
        case _: impl.Lit => Nil
      }
      tree.require[impl.Scope] match {
        case tree: impl.Term.Block => membersOfStats(tree.stats)
        case tree: impl.Term.Function => tree.params
        case tree: impl.Term.For => tree.enums.flatMap(membersOfEnumerator)
        case tree: impl.Term.ForYield => tree.enums.flatMap(membersOfEnumerator)
        case tree: impl.Term.Param => Nil
        case tree: impl.Type => implicitly[SemanticContext].members(tree)
        case tree: impl.Type.Param => tree.tparams
        case tree: impl.Pat.Var.Term => Nil
        case tree: impl.Pat.Var.Type => Nil
        case tree: impl.Decl.Def => tree.tparams ++ mergeEvidences(tree.paramss, tree.tparams.flatMap(deriveEvidences)).flatten
        case tree: impl.Decl.Type => tree.tparams
        case tree: impl.Defn.Def => tree.tparams ++ mergeEvidences(tree.paramss, tree.tparams.flatMap(deriveEvidences)).flatten
        case tree: impl.Defn.Macro => tree.tparams ++ mergeEvidences(tree.paramss, tree.tparams.flatMap(deriveEvidences)).flatten
        case tree: impl.Defn.Type => tree.tparams
        case tree: impl.Defn.Class => tree.tparams ++ tree.tpe.members
        case tree: impl.Defn.Trait => tree.tparams ++ tree.tpe.members
        case tree: impl.Defn.Object => Nil ++ tree.tpe.members
        case tree: impl.Pkg => tree.tpe.members
        case tree: impl.Pkg.Object => Nil ++ tree.tpe.members
        case tree: impl.Ctor.Primary => mergeEvidences(tree.paramss, tree.owner.tparams.flatMap(deriveEvidences)).flatten
        case tree: impl.Ctor.Secondary => mergeEvidences(tree.paramss, tree.owner.tparams.flatMap(deriveEvidences)).flatten
        case tree: impl.Case => membersOfPat(tree.pat)
      }
    }
    @hosted private[meta] def internalFilter[T: ClassTag](filter: T => Boolean): Seq[T] = {
      internalAll.collect{ case x: T => x }.filter(filter)
    }
    @hosted private[meta] def internalSingle[T <: Member : ClassTag](filter: T => Boolean, diagnostic: String): T = {
      val filtered = internalFilter[T](filter)
      filtered match {
        case Seq() => throw new SemanticException(s"""no $diagnostic found in ${showSummary(tree)}""")
        case Seq(single) => single
        case Seq(_, _*) => throw new SemanticException(s"""multiple $diagnostic found in ${showSummary(tree)}""")
      }
    }
    @hosted private[meta] def internalSingleNamed[T <: Member : ClassTag](name: String, filter: T => Boolean, diagnostic: String): T = {
      val filtered = internalFilter[T](x => x.name.toString == name && filter(x))
      filtered match {
        case Seq() => throw new SemanticException(s"""no $diagnostic named "$name" found in ${showSummary(tree)}""")
        case Seq(single) => single
        case Seq(_, _*) => throw new SemanticException(s"""multiple $diagnostic named "$name" found in ${showSummary(tree)}""")
      }
    }
    @hosted private[meta] def internalMulti[T <: Member : ClassTag](name: String, filter: T => Boolean, diagnostic: String): Seq[T] = {
      val filtered = internalFilter[T](x => x.name.toString == name && filter(x))
      filtered match {
        case Seq() => throw new SemanticException(s"""no $diagnostic named "$name" found in ${showSummary(tree)}""")
        case Seq(single) => List(single)
        case Seq(multi @ _*) => multi.toList
      }
    }
    @hosted def members: Seq[Member] = internalFilter[Member](_ => true)
    @hosted def members[T : ClassTag, U : ClassTag](param: T)(implicit ev: XtensionMembersSignature[T, U]): U = param match {
      case name: Name =>
        name match {
          case name: Term.Name => internalSingleNamed[Member.Term](name.toString, _ => true, "term members").require[U]
          case name: Type.Name => internalSingleNamed[Member.Type](name.toString, _ => true, "type members").require[U]
          case _ => throw new SemanticException(s"""no member named $name found in ${showSummary(tree)}""")
        }
      case member: Member =>
        member.name match {
          case thisName: impl.Name =>
            internalFilter[T](that => {
              val thisDenot = thisName.denot
              val thatDenot = that.require[impl.Member].name.require[impl.Name].denot
              val bothNonEmpty = thisDenot != s.Denotation.Zero && thatDenot != s.Denotation.Zero
              val bothMeanTheSameThing = thisDenot.symbols == thatDenot.symbols
              bothNonEmpty && bothMeanTheSameThing
            }) match {
              case Seq() => throw new SemanticException(s"no prototype for $member found in ${showSummary(tree)}")
              case Seq(single) => single.require[U]
              case _ => unreachable(debug(member, member.show[Structure]))
            }
        }
      case _ =>
        unreachable(debug(param, param.getClass))
    }
    @hosted def packages: Seq[Member.Term] = internalFilter[Member.Term](_.isPackage)
    @hosted def packages(name: String): Member.Term = internalSingleNamed[Member.Term](name, _.isPackage, "packages")
    @hosted def packages(name: scala.Symbol): Member.Term = packages(name.toString)
    @hosted def packageObject: Member.Term = internalSingle[impl.Pkg.Object](_.isPackageObject, "package objects")
    @hosted def ctor: Member.Term = internalSingle[Member.Term](_.isPrimaryCtor, "primary constructors")
    @hosted def ctors: Seq[Member.Term] = internalFilter[Member.Term](_.isCtor)
    @hosted def classes: Seq[Member.Type] = internalFilter[Member.Type](_.isClass)
    @hosted def classes(name: String): Member.Type = internalSingleNamed[Member.Type](name, _.isClass, "classes")
    @hosted def classes(name: scala.Symbol): Member.Type = classes(name.toString)
    @hosted def traits: Seq[Member.Type] = internalFilter[Member.Type](_.isTrait)
    @hosted def traits(name: String): Member.Type = internalSingleNamed[Member.Type](name, _.isTrait, "traits")
    @hosted def traits(name: scala.Symbol): Member.Type = traits(name.toString)
    @hosted def objects: Seq[Member.Term] = internalFilter[Member.Term](_.isObject)
    @hosted def objects(name: String): Member.Term = internalSingleNamed[Member.Term](name, _.isObject, "objects")
    @hosted def objects(name: scala.Symbol): Member.Term = objects(name.toString)
    @hosted def vars: Seq[Member.Term] = internalFilter[impl.Pat.Var.Term](_.isVar)
    @hosted def vars(name: String): Member.Term = internalSingleNamed[impl.Pat.Var.Term](name, _.isVar, "vars")
    @hosted def vars(name: scala.Symbol):Member.Term = vars(name.toString)
    @hosted def vals: Seq[Member.Term] = internalFilter[impl.Pat.Var.Term](_.isVal)
    @hosted def vals(name: String): Member.Term = internalSingleNamed[impl.Pat.Var.Term](name, _.isVal, "vals")
    @hosted def vals(name: scala.Symbol): Member.Term = vals(name.toString)
    @hosted def defs: Seq[Member.Term] = internalFilter[Member.Term](_.isDef)
    @hosted def defs(name: String): Member.Term = internalSingleNamed[Member.Term](name, _.isDef, "defs")
    @hosted def defs(name: scala.Symbol): Member.Term = defs(name.toString)
    @hosted def overloads(name: String): Seq[Member.Term] = internalMulti[Member.Term](name, _.isDef, "defs")
    @hosted def overloads(name: scala.Symbol): Seq[Member.Term] = overloads(name.toString)
    @hosted def types: Seq[Member.Type] = internalFilter[Member.Type](m => m.isAbstractType || m.isAliasType)
    @hosted def types(name: String): Member.Type = internalSingleNamed[Member.Type](name, m => m.isAbstractType || m.isAliasType, "types")
    @hosted def types(name: scala.Symbol): Member.Type = types(name.toString)
    @hosted def params: Seq[Term.Param] = internalFilter[Term.Param](_ => true)
    @hosted def paramss: Seq[Seq[Term.Param]] = tree match {
      case tree: impl.Decl.Def => mergeEvidences(tree.paramss, tree.tparams.flatMap(deriveEvidences))
      case tree: impl.Defn.Def => mergeEvidences(tree.paramss, tree.tparams.flatMap(deriveEvidences))
      case tree: impl.Defn.Macro => mergeEvidences(tree.paramss, tree.tparams.flatMap(deriveEvidences))
      case tree: impl.Ctor.Primary => mergeEvidences(tree.paramss, tree.tparams.flatMap(deriveEvidences))
      case tree: impl.Ctor.Secondary => mergeEvidences(tree.paramss, tree.tparams.flatMap(deriveEvidences))
      case _ => Nil
    }
    @hosted def params(name: String): Term.Param = internalSingleNamed[Term.Param](name, _ => true, "parameters")
    @hosted def params(name: scala.Symbol): Term.Param = params(name.toString)
    @hosted def tparams: Seq[Type.Param] = internalFilter[Type.Param](_ => true)
    @hosted def tparams(name: String): Type.Param = internalSingleNamed[Type.Param](name, _ => true, "type parameters")
    @hosted def tparams(name: scala.Symbol): Type.Param = tparams(name.toString)
  }

  implicit class XtensionSemanticScope(scope: Scope) extends XtensionSemanticScopeLike {
    @hosted protected def tree: Scope = scope
  }

  implicit class XtensionSemanticRefScopeLike(ref: Ref) extends XtensionSemanticScopeLike {
    @hosted protected def tree: Scope = ref.defn
  }

  implicit class XtensionSemanticTypeRefScopeLike(ref: Type.Ref) extends XtensionSemanticScopeLike {
    @hosted protected def tree: Scope = ref
  }

  // ===========================
  // PART 8: ALIASES
  // ===========================
  type SemanticContext = scala.meta.semantic.Context

  // TODO: Previously, we had a `dialectFromSemanticContext` implicit, which obviated the need in this method.
  // However, this dialect materializer was really half-hearted in the sense that it worked for prettyprinting
  // but not for quasiquotes (since quasiquotes need a dialect at compile time, not a potentially runtime dialect).
  // Until this problem is fixed, I'm disabling the materializer altogether.
  private def showSummary(tree: Tree)(implicit c: SemanticContext) {
    implicit val d: Dialect = c.dialect
    tree.show[Summary]
  }
}

private[meta] trait Aliases {
  type SemanticException = scala.meta.semantic.SemanticException
  val SemanticException = scala.meta.semantic.SemanticException
}
