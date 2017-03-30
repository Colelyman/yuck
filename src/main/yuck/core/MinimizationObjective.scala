package yuck.core

import scala.math._

/**
 * Objective for minimizing the value of a variable.
 *
 * @author Michael Marte
 */
final class MinimizationObjective
    [Value <: NumericalValue[Value]]
    (x: Variable[Value],
     override val targetCosts: Value,
     maybeTighteningStep: Option[Value])
    (implicit valueTraits: NumericalValueTraits[Value])
    extends NumericalObjective[Value](x)
{
    require(maybeTighteningStep.isEmpty || maybeTighteningStep.get < valueTraits.zero)
    override def toString =
        "min %s".format(x)
    override def isSolution(costs: Costs) =
        costs.asInstanceOf[Value] <= targetCosts
    override def compareCosts(lhs: Costs, rhs: Costs) =
        lhs.asInstanceOf[Value].compare(rhs.asInstanceOf[Value])
    protected override def computeDelta(before: SearchState, after: SearchState) =
        costs(after) - costs(before)
    override def tighten(space: Space, rootObjective: AnyObjective) = {
        if (maybeTighteningStep.isDefined) {
            tighten(space, rootObjective, maybeTighteningStep.get)
        } else {
            (space.searchState, None)
        }
    }
}
