package catalyst
package optimizer

import org.scalatest.FunSuite

import types.IntegerType
import util._
import plans.logical.{LogicalPlan, LocalRelation}
import expressions._
import dsl._

/* Implicit conversions for creating query plans */

class OptimizerSuite extends FunSuite {
  // Test relations.  Feel free to create more.
  val testRelation = LocalRelation('a.int, 'b.int, 'c.int)

  // Helper functions for comparing plans.

  /**
   * Since attribute references are given globally unique ids during analysis we must normalize them to check if two
   * different queries are identical.
   */
  protected def normalizeExprIds(plan: LogicalPlan) = {
    val minId = plan.flatMap(_.expressions.flatMap(_.references).map(_.exprId.id)).min
    plan transformAllExpressions {
      case a: AttributeReference =>
        AttributeReference(a.name, a.dataType, a.nullable)(exprId = ExprId(a.exprId.id - minId))
    }
  }

  /** Fails the test if the two plans do not match */
  protected def comparePlans(plan1: LogicalPlan, plan2: LogicalPlan) {
    val normalized1 = normalizeExprIds(plan1)
    val normalized2 = normalizeExprIds(plan2)
    if(normalized1 != normalized2)
      fail(
        s"""
          |== FAIL: Plans do not match ===
          |${sideBySide(normalized1.treeString, normalized2.treeString).mkString("\n")}
        """.stripMargin)
  }

  // This test already passes.
  test("eliminate subqueries") {
    val originalQuery =
      testRelation
        .subquery('y)
        .select('a)

    val optimized = Optimize(originalQuery.analyze)
    val correctAnswer =
      testRelation
        .select('a.attr)
        .analyze

    comparePlans(optimized, correctAnswer)
  }

  /*
  * Unit tests for constant folding in expressions.
  * */
  test("Constant folding test: expressions only have literals") {
    val originalQuery =
      testRelation
        .select(Literal(2) + Literal(3) + Literal(4) as Symbol("2+3+4"),
                Literal(2) * Literal(3) + Literal(4) as Symbol("2*3+4"),
                Literal(2) * (Literal(3) + Literal(4)) as Symbol("2*(3+4)"))
        .where(Literal(1) === Literal(1) &&
               Literal(2) > Literal(3) ||
               Literal(3) > Literal(2) )
        .groupBy(Literal(2) * Literal(3) - Literal(6) / (Literal(4) - Literal(2)))(Literal(9) / Literal(3) as Symbol("9/3"))

    val optimized = Optimize(originalQuery.analyze)

    val correctAnswer =
      testRelation
        .select(Literal(9) as Symbol("2+3+4"),
          Literal(10) as Symbol("2*3+4"),
          Literal(14) as Symbol("2*(3+4)"))
        .where(Literal(true))
        .groupBy(Literal(3))(Literal(3) as Symbol("9/3"))
        .analyze

    comparePlans(optimized, correctAnswer)
  }

  test("Constant folding test: expressions have attribute references and literals in" +
    "arithmetic operations") {
    val originalQuery =
      testRelation
        .select(Literal(2) + Literal(3) + 'a as Symbol("c1"),
                'a + Literal(2) + Literal(3) as Symbol("c2"),
                Literal(2) * 'a + Literal(4) as Symbol("c3"),
                'a * (Literal(3) + Literal(4)) as Symbol("c4"))

    val optimized = Optimize(originalQuery.analyze)

    val correctAnswer =
      testRelation
        .select(Literal(5) + 'a as Symbol("c1"),
                'a + Literal(2) + Literal(3) as Symbol("c2"),
                Literal(2) * 'a + Literal(4) as Symbol("c3"),
                'a * (Literal(7)) as Symbol("c4"))
        .analyze

    comparePlans(optimized, correctAnswer)
  }

  test("Constant folding test: expressions have attribute references and literals in" +
    "predicates") {
    val originalQuery =
      testRelation
        .where((('a > 1 && Literal(1) === Literal(1)) ||
               ('a < 10 && Literal(1) === Literal(2)) ||
               (Literal(1) === Literal(1) && 'b > 1) ||
               (Literal(1) === Literal(2) && 'b < 10)) &&
               (('a > 1 || Literal(1) === Literal(1)) &&
               ('a < 10 || Literal(1) === Literal(2)) &&
               (Literal(1) === Literal(1) || 'b > 1) &&
               (Literal(1) === Literal(2) || 'b < 10)))

    val optimized = Optimize(originalQuery.analyze)

    val correctAnswer =
      testRelation
        .where(('a > 1 ||
               'b > 1) &&
               ('a < 10 &&
               'b < 10))
        .analyze

    comparePlans(optimized, correctAnswer)
  }

  test("Constant folding test: expressions have foldable functions") {
    val originalQuery =
      testRelation
        .select(Cast(Literal("2"), IntegerType) + Literal(3) + 'a as Symbol("c1"),
                Coalesce(Seq(Cast(Literal("abc"), IntegerType), Literal(3))) as Symbol("c2"))

    val optimized = Optimize(originalQuery.analyze)

    val correctAnswer =
      testRelation
        .select(Literal(5) + 'a as Symbol("c1"),
                Literal(3) as Symbol("c2"))
        .analyze

    comparePlans(optimized, correctAnswer)
  }

  test("Constant folding test: expressions have nonfoldable functions") {
    val originalQuery =
      testRelation
        .select(Rand + Literal(1) as Symbol("c1"),
                Sum('a) as Symbol("c2"))

    val optimized = Optimize(originalQuery.analyze)

    val correctAnswer =
      testRelation
        .select(Rand + Literal(1.0) as Symbol("c1"),
                Sum('a) as Symbol("c2"))
        .analyze

    comparePlans(optimized, correctAnswer)
  }
}