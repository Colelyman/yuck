package yuck.core.test

import org.junit._

import yuck.core._
import yuck.util.testing.{OrderingTestHelper, UnitTest}

/**
 * @author Michael Marte
 *
 */
@Test
@FixMethodOrder(runners.MethodSorters.NAME_ASCENDING)
final class IntegerSetValueOrderingTest extends UnitTest with IntegerSetValueTestData {

    override protected val randomGenerator = new JavaRandomGenerator

    @Test
    def testOrdering: Unit = {
        val helper = new OrderingTestHelper[IntegerSetValue](randomGenerator)
        val ord1 = IntegerSetValueOrdering
        helper.testOrdering(testData, ord1)
        val ord2 = new Ordering[IntegerSetValue] {
            override def compare(a: IntegerSetValue, b: IntegerSetValue) = a.compare(b)
        }
        assertEq(testData.sorted(ord1), testData.sorted(ord2))
    }

}
