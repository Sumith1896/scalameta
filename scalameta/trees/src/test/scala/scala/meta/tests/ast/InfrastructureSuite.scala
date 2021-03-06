package scala.meta.tests
package ast

import org.scalatest._
import scala.meta.tokens._
import scala.meta.tokenquasiquotes._
import scala.meta.prettyprinters._
import scala.meta.internal.ast._
import scala.meta.internal.semantic._
import scala.meta.dialects.Scala211

class InfrastructureSuite extends FunSuite {
  test("become for Quasi-0") {
    val q = Term.Quasi(0, "hello").withTokens(toks"Hello")
    assert(q.become[Type.Quasi].show[Structure] === """Type.Quasi(0, "hello")""")
    assert(q.become[Type.Quasi].tokens.toString === "Synthetic(Vector(Hello (0..5)))")
  }

  test("become for Quasi-1") {
    val q = Term.Quasi(1, Term.Quasi(0, "hello").withTokens(toks"HelloInner")).withTokens(toks"HelloOuter")
    assert(q.become[Type.Quasi].show[Structure] === """Type.Quasi(1, Type.Quasi(0, "hello"))""")
    assert(q.become[Type.Quasi].tokens.toString === "Synthetic(Vector(HelloOuter (0..10)))")
  }

  private def attributeName(name: Term.Name): Term.Name = name.withAttrs(Denotation.Single(Prefix.Zero, Symbol.RootPackage), Foo.setTypechecked)
  private def attributeName(name: Type.Name): Type.Name = name.withAttrs(Denotation.Single(Prefix.Zero, Symbol.RootPackage))
  private def attributeTerm(term: Term): Term = term.withAttrs(Foo.setTypechecked)
  private def Foo = attributeName(Type.Name("Foo"))
  private def foo = attributeName(Term.Name("foo"))
  private def bar = attributeName(Term.Name("bar"))
  private def foobar = attributeTerm(Term.Select(foo, bar))

  test("TYPECHECKED setters") {
    val x1 = foo
    assert(x1.isTypechecked === false)
    val x2 = x1.setTypechecked
    assert(x2.isTypechecked === true)
    val x3 = x2.resetTypechecked
    assert(x3.isTypechecked === false)
    val x4 = foo.withTypechecked(true)
    assert(x4.isTypechecked === true)
    val x5 = foo.withTypechecked(false)
    assert(x5.isTypechecked === false)
  }

  test("TYPECHECKED doesn't reset when tokens are touched") {
    val x1 = foo.setTypechecked
    val x2 = x1.withTokens(Tokens())
    assert(x1.isTypechecked == true)
    assert(x2.isTypechecked == true)
  }

  // NOTE: This situation is impossible as long as we validate state transitions,
  // i.e. when we require that withAttrs can only be called on unattributed trees
  // and withExpansion can only be called on partially attributed trees.
  //
  // test("TYPECHECKED resets when attributes are touched") {
  //   val x1 = foo.setTypechecked
  //   val x2 = x1.withAttrs(Denotation.Zero, Typing.Zero)
  //   val x3 = x1.withExpansion(Expansion.Zero)
  //   assert(x1.isTypechecked == true)
  //   assert(x2.isTypechecked == false)
  //   assert(x3.isTypechecked == false)
  // }

  test("TYPECHECKED resets when the tree is copied") {
    val x1 = foo.setTypechecked
    val x2 = x1.copy(value = "bar")
    assert(x1.isTypechecked == true)
    assert(x2.isTypechecked == false)
  }

  test("TYPECHECKED doesn't reset when the tree is unquoted") {
    val x1 = foo.setTypechecked
    val x2 = Term.Select(x1, Term.Name("bar"))
    assert(x1.isTypechecked == true)
    assert(x2.isTypechecked == false)
    assert(x2.qual.isTypechecked == true)
    assert(x2.name.isTypechecked == false)
  }

  test("TYPECHECKED sets children when set") {
    val x1 = foo
    val x2 = bar
    val x3 = foobar
    val y3 @ Term.Select(y1, y2) = x3.setTypechecked
    assert(y1.isTypechecked === true)
    assert(y2.isTypechecked === true)
    assert(y3.isTypechecked === true)
  }

  test("TYPECHECKED resets children when reset") {
    val x1 = foo.setTypechecked
    val x2 = bar.setTypechecked
    val x3 = foobar.setTypechecked
    val y3 @ Term.Select(y1, y2) = x3.resetTypechecked
    assert(y1.isTypechecked === false)
    assert(y2.isTypechecked === false)
    assert(y3.isTypechecked === false)
  }

  test("TYPECHECKED crashes when denot is zero") {
    val x1 = foo.privateCopy(denot = Denotation.Zero)
    intercept[UnsupportedOperationException] { x1.setTypechecked }
  }

  test("TYPECHECKED crashes when typing is zero") {
    val x1 = foo.privateCopy(typing = Typing.Zero)
    intercept[UnsupportedOperationException] { x1.setTypechecked }
  }

  test("TYPECHECKED crashes when expansion is zero") {
    val x1 = foo.privateCopy(expansion = Expansion.Zero)
    intercept[UnsupportedOperationException] { x1.setTypechecked }
  }

  test("Typing.Nonrecursive is really lazy") {
    val x1 = Typing.Nonrecursive(??? : Type)
    val x2 = x1.map(_ => ??? : Type)
    val x3 = Term.Name("foo").withAttrs(foo.denot, ??? : Type)
    assert(x3.isTypechecked === false)
  }

  private def denot = Denotation.Single(Prefix.Zero, Symbol.RootPackage)
  private def typing = Foo.setTypechecked
  private def expansion = u.withAttrs(denot, typing).setTypechecked
  private def u = Term.Name("u")
  private def pa1 = u.withAttrs(denot, typing)
  private def pa2 = u.withExpansion(expansion)
  private def pa3 = u.withAttrs(denot, typing).withExpansion(expansion)
  private def a = pa3.setTypechecked

  implicit class XtensionStateTree(tree: Tree) {
    private def invoke(name: String): Boolean = {
      val m = tree.getClass.getMethods().find(_.getName == name).get
      m.setAccessible(true)
      m.invoke(tree).asInstanceOf[Boolean]
    }
    def isU = tree.isUnattributed
    def isPA = tree.isPartiallyAttributed
    def isA = tree.isAttributed
  }

  test("states") {
    assert(u.isU === true)
    assert(u.isPA === false)
    assert(u.isA === false)

    assert(pa1.isU === false)
    assert(pa1.isPA === true)
    assert(pa1.isA === false)
    assert(pa2.isU === false)
    assert(pa2.isPA === true)
    assert(pa2.isA === false)
    assert(pa3.isU === false)
    assert(pa3.isPA === true)
    assert(pa3.isA === false)

    assert(a.isU === false)
    assert(a.isPA === false)
    assert(a.isA === true)
  }

  test("unquoting transitions") {
    val Term.Select(u1, _) = Term.Select(u, Term.Name("bar"))
    assert(u1.isU === true)

    val Term.Select(pa11, _) = Term.Select(pa1, Term.Name("bar"))
    val Term.Select(pa21, _) = Term.Select(pa2, Term.Name("bar"))
    val Term.Select(pa31, _) = Term.Select(pa3, Term.Name("bar"))
    assert(pa11.isPA === true)
    assert(pa21.isPA === true)
    assert(pa31.isPA === true)

    val Term.Select(a1, _) = Term.Select(a, Term.Name("bar"))
    assert(a1.isA === true)
  }

  test("copy transitions") {
    val u1 = u.copy()
    assert(u1.isU === true)

    val pa11 = pa1.copy()
    val pa21 = pa2.copy()
    val pa31 = pa3.copy()
    assert(pa11.isU === true)
    assert(pa21.isU === true)
    assert(pa31.isU === true)

    val a1 = a.copy()
    assert(a1.isU === true)
  }

  test("withAttrs transitions") {
    val u1 = u.withAttrs(denot, typing)
    assert(u1.isPA === true)

    val pa11 = pa1.withAttrs(denot, typing)
    val pa21 = pa2.withAttrs(denot, typing)
    val pa31 = pa3.withAttrs(denot, typing)
    assert(pa11.isPA === true)
    assert(pa21.isPA === true)
    assert(pa31.isPA === true)

    intercept[UnsupportedOperationException] { a.withAttrs(denot, typing) }
  }

  test("withExpansion transitions") {
    val u1 = u.withExpansion(expansion)
    assert(u1.isPA === true)

    val pa11 = pa1.withExpansion(expansion)
    val pa21 = pa2.withExpansion(expansion)
    val pa31 = pa3.withExpansion(expansion)
    assert(pa11.isPA === true)
    assert(pa21.isPA === true)
    assert(pa31.isPA === true)

    intercept[UnsupportedOperationException] { a.withExpansion(expansion) }
  }

  test("setTypechecked transitions") {
    intercept[UnsupportedOperationException] { u.setTypechecked }

    val pa11 = pa1.setTypechecked
    assert(pa11.isA === true)
    intercept[UnsupportedOperationException] { pa2.setTypechecked }
    val pa31 = pa3.setTypechecked
    assert(pa31.isA === true)

    val a1 = a.setTypechecked
    assert(a1.isA === true)
  }
}