package yuck.constraints

import scala.collection._

import yuck.core._

/**
 * @author Michael Marte
 *
 */
final class LinearCombination
    [Value <: NumericalValue[Value]]
    (id: Id[Constraint], goal: Goal,
     val axs: immutable.Seq[AX[Value]], y: NumericalVariable[Value])
    (implicit valueTraits: NumericalValueTraits[Value])
    extends Constraint(id, goal)
{

    override def toString = "%s = sum([%s])".format(y, axs.mkString(", "))
    override def inVariables = axs.toIterator.map(_.x)
    override def outVariables = List(y)

    private val id2ax = {
        val c = AX.compact(axs.toList)
        immutable.HashMap[AnyVariable, AX[Value]]() ++ (c.map(_.x).zip(c))
    }
    private var sum = valueTraits.zero
    private val effects = List(new ReusableEffectWithFixedVariable[Value](y))
    private val effect = effects.head

    override def propagate = {
        val lhs0 = new Iterable[(Value, NumericalDomain[Value])] {
            override def iterator = axs.toIterator.map(ax => (ax.a, ax.x.domain))
        }
        val rhs0 = y.domain
        val (lhs1, rhs1) = valueTraits.domainPruner.linEq(lhs0, rhs0)
        Variable.pruneDomains(axs.toIterator.map(_.x).zip(lhs1.toIterator)) ||| y.pruneDomain(rhs1)
    }

    override def initialize(now: SearchState) = {
        sum = valueTraits.zero
        for ((_, ax) <- id2ax) {
            sum += ax.a * now.value(ax.x)
        }
        effect.a = sum
        effects
    }

    override def consult(before: SearchState, after: SearchState, move: Move) = {
        effect.a = sum
        for (x <- move.involvedVariables) {
            val ax = id2ax.get(x).get
            effect.a = effect.a.addAndSub(ax.a, after.value(ax.x), before.value(ax.x))
        }
        effects
    }

    override def commit(before: SearchState, after: SearchState, move: Move) = {
        sum = effect.a
        effects
    }

}
