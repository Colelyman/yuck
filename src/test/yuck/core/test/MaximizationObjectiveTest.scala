package yuck.core.test

import org.junit._

import yuck.core._
import yuck.util.testing.UnitTest

/**
  * @author Michael Marte
  *
  */
@Test
@FixMethodOrder(runners.MethodSorters.NAME_ASCENDING)
final class MaximizationObjectiveTest extends UnitTest {

    private val space = new Space(logger, sigint)
    private val baseDomain = new IntegerRange(Zero, Nine)
    private val x = new IntegerVariable(space.nextVariableId, "x", baseDomain)
    private val objective = new MaximizationObjective(x, Some(baseDomain.ub - One), Some(One))

    @Test
    def testBasics: Unit = {
        assertEq(objective.optimizationMode, OptimizationMode.Max)
        assertEq(objective.targetCosts, baseDomain.ub - One)
        assertEq(objective.primitiveObjectives, Seq(objective))
        assertEq(objective.objectiveVariables, Seq(x))
        val now = space.searchState
        for (a <- x.domain.values) {
            space.setValue(x, a)
            assertEq(objective.costs(now), a)
            val isSolution = a >= baseDomain.ub - One
            val isOptimal = a == baseDomain.ub
            assertEq(objective.isSolution(a), isSolution)
            assertEq(objective.isSolution(now), isSolution)
            assertEq(objective.isGoodEnough(a), isSolution)
            assertEq(objective.isGoodEnough(now), isSolution)
            assertEq(objective.isOptimal(a), isOptimal)
            assertEq(objective.isOptimal(now), isOptimal)
        }
    }

    @Test
    def testCostComparison: Unit = {
        val a = new Assignment
        a.setValue(x, Zero)
        assertEq(objective.costs(a), Zero)
        assertEq(objective.compareCosts(Zero, Zero), 0)
        assertGt(objective.compareCosts(Zero, One), 0)
        assertLt(objective.compareCosts(One, Zero), 0)
        assertEq(objective.assessMove(a, a), 0)
    }

    @Test
    def testMoveAssessment: Unit = {
        import scala.math.Ordering.Double.TotalOrdering
        val a = new Assignment
        a.setValue(x, Zero)
        val b = new Assignment
        b.setValue(x, One)
        assertEq(objective.assessMove(b, b), 0)
        assertEq(objective.assessMove(a, b), -1)
        assertEq(objective.assessMove(a, b), -1)
        b.setValue(x, Two)
        assertLt(objective.assessMove(a, b), -1)
        objective.assessMove(b, a)
        assertGt(objective.assessMove(b, a), 1)
    }

    @Test
    def testTightening: Unit = {
        space
            .post(new DummyConstraint(space.nextConstraintId, List(x), Nil))
            .setValue(x, Eight)
            .initialize
        // check that tightening finds upper bound of x
        if (true) {
            val TighteningResult(tightenedState, maybeTightenedVariable) = objective.tighten(space)
            assertEq(tightenedState.mappedVariables, Set(x))
            assertEq(tightenedState.value(x), Nine)
            assertEq(maybeTightenedVariable, Some(x))
            assertEq(space.searchState.value(x), Nine)
            assert(x.domain.isSingleton)
            assertEq(x.domain.singleValue, Nine)
        }
        // check that further tightening is not possible
        if (true) {
            val TighteningResult(_, maybeTightenedObjective) = objective.tighten(space)
            assertEq(maybeTightenedObjective, None)
        }
    }

}
