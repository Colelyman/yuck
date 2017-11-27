package yuck.constraints.test

import org.junit._

import scala.collection._

import yuck.constraints._
import yuck.core._
import yuck.util.testing.UnitTest

/**
 * @author Michael Marte
 *
 */
@Test
@FixMethodOrder(runners.MethodSorters.NAME_ASCENDING)
final class TableTest extends UnitTest {

    @Test
    def testIntegerTable1 {
        val space = new Space(logger)
        val d = new IntegerRange(Zero, Nine)
        val s = space.createVariable("s", d)
        val t = space.createVariable("t", d)
        val u = space.createVariable("u", d)
        val costs = space.createVariable("costs", NonNegativeIntegerRange)
        val rows = immutable.IndexedSeq(immutable.IndexedSeq(0, 0, 0), immutable.IndexedSeq(1, 2, 3))
        val c = new IntegerTable(space.constraintIdFactory.nextId, null, immutable.IndexedSeq(s, t, u), rows, costs)
        space
            .post(c)
            .setValue(s, One)
            .setValue(t, One)
            .setValue(u, One)
            .initialize
        assertEq(space.searchVariables, Set(s, t, u))
        val now = space.searchState
        assertEq(now.value(costs), Three)
        if (true) {
            // move away from the first row and approach the second row
            val move = new ChangeValue(space.moveIdFactory.nextId, t, Two)
            val after = space.consult(move)
            assertEq(now.value(t), One)
            assertEq(now.value(costs), Three)
            assertEq(after.value(t), Two)
            assertEq(after.value(costs), Two)
            space.commit(move)
            assertEq(now.value(t), Two)
            assertEq(now.value(costs), Two)
        }
        if (true) {
            // change two values at once
            val move =
                new ChangeValues(
                    space.moveIdFactory.nextId,
                    List(new ImmutableEffect(s, Zero), new ImmutableEffect(u, Three)))
            val after = space.consult(move)
            assertEq(now.value(s), One)
            assertEq(now.value(u), One)
            assertEq(now.value(costs), Two)
            assertEq(after.value(s), Zero)
            assertEq(after.value(u), Three)
            assertEq(after.value(costs), One)
            space.commit(move)
            assertEq(now.value(s), Zero)
            assertEq(now.value(u), Three)
            assertEq(now.value(costs), One)
        }
        space.initialize
        assertEq(now.value(costs), One)
    }

    // This test considers a situation that caused problems due to a bug in the
    // deletion of infeasible table rows.
    @Test
    def testIntegerTable2 {
        val space = new Space(logger)
        val s = space.createVariable("s", new IntegerRange(Two, Five))
        val t = space.createVariable("t", new IntegerRange(Two, Two))
        val costs = space.createVariable("costs", NonNegativeIntegerRange)
        val rows =
            immutable.IndexedSeq(
                0, 0,
                1, 0, 1, 1, 1, 4,
                2, 0, 2, 2,
                3, 0, 3, 3,
                4, 0, 4, 4,
                5, 0, 5, 1, 5, 2, 5, 3, 5, 4)
                .grouped(2).toIndexedSeq
        val c = new IntegerTable(space.constraintIdFactory.nextId, null, immutable.IndexedSeq(s, t), rows, costs)
        space.post(c).setValue(s, Two).setValue(t, Two).initialize
        assertEq(space.searchVariables, Set(s))
        val now = space.searchState
        assertEq(now.value(costs), Zero)
        space.setValue(s, Three).initialize
        assertEq(now.value(costs), One)
        space.setValue(s, Four).initialize
        assertEq(now.value(costs), One)
        space.setValue(s, Five).initialize
        assertEq(now.value(costs), Zero)
    }

}
