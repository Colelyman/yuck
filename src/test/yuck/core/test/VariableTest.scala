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
final class VariableTest extends UnitTest {

    @Test
    def testVariableEquality {
        val space = new Space(logger)
        val d = new IntegerRange(Zero, Nine)
        val s = space.createVariable("s", d)
        val t = space.createVariable("t", d)
        assertEq(s, s)
        assertEq(t, t)
        assertNe(s, t)
    }

    @Test
    def testVariableCasting {
        val space = new Space(logger)
        val b = space.createVariable("b", CompleteBooleanDomain)
        val i = space.createVariable("i", CompleteIntegerRange)
        BooleanValueTraits.unsafeDowncast(b)
        BooleanValueTraits.unsafeDowncast(i)
        BooleanValueTraits.safeDowncast(b)
        BooleanValueTraits.unsafeDowncast[List](List(b, i))
        assertEx(BooleanValueTraits.safeDowncast(i))
        assertEx(IntegerValueTraits.safeDowncast(b))
        BooleanValueTraits.safeDowncast[List](List(b))
        assertEx(BooleanValueTraits.safeDowncast[List](List(b, i)))
        IntegerValueTraits.safeDowncast(i)
        val foo = space.createVariable("foo", new IntegerPowersetDomain(CompleteIntegerRange))
        val bar = space.createVariable("bar", new SingletonIntegerSetDomain(CompleteIntegerRange))
        IntegerSetValueTraits.safeDowncast(foo)
        IntegerSetValueTraits.safeDowncast(bar)
        IntegerSetValueTraits.safeDowncast[List](List(foo, bar))
        assertEx(IntegerSetValueTraits.safeDowncast[List](List(b, i)))
    }

}
