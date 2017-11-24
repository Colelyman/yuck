package yuck.constraints.test

import org.junit._

/**
 * @author Michael Marte
 *
 */
@runner.RunWith(classOf[runners.Suite])
@runners.Suite.SuiteClasses(
    Array(
        classOf[BasicNumericalConstraintsTest],
        classOf[AlldistinctTest],
        classOf[BinPackingTest],
        classOf[CountConstTest],
        classOf[CountVarTest],
        classOf[CumulativeTest],
        classOf[DisjunctionTest],
        classOf[ElementTest],
        classOf[InverseTest],
        classOf[LexLessEqTest],
        classOf[LexLessTest],
        classOf[LinearCombinationTest],
        classOf[MaximumTest],
        classOf[MinimumTest],
        classOf[NumberOfDistinctValuesTest],
        classOf[RegularTest],
        classOf[ReificationTest],
        classOf[SumTest],
        classOf[TableTest]))
@Test
final class ConstraintTestSuite {
}
