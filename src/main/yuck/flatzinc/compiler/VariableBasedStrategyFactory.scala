package yuck.flatzinc.compiler

import scala.collection._

import yuck.constraints.DistributionMaintainer
import yuck.constraints.LinearCombination
import yuck.constraints.Sum
import yuck.core._
import yuck.flatzinc.ast.IntConst
import yuck.constraints.Alldistinct

/**
 * Generates focused move generators for satisfaction and minimization goals.
 *
 * The generation of a focused move generator departs from the variable x to minimize.
 * Say Y is the set of search variables involved in the minimization of x.
 * For each y in Y, a so-called hot-spot indicator variable h(y) is created that reflects
 * the influence of y on x, see createHotSpotIndicators.
 *
 * Given some subset Z of Y, a move generator over Z is assembled on the basis of a distribution
 * of size |Z| the frequencies of which will be kept in sync with the values of the h(z), z in Z.
 *
 * To decouple this factory from the compiler, we preserve the order of variables in arrays.
 * In particular, we avoid the usage of hash sets because the hash codes/ variable ids depend on
 * details of the compiler implementation.
 *
 * @author Michael Marte
 */
final class VariableBasedStrategyFactory
    (cc: CompilationContext, randomGenerator: RandomGenerator)
    extends StrategyFactory(cc, randomGenerator)
{

    private lazy val searchVariables = space.searchVariables

    protected override def createMoveGeneratorForSatisfactionGoal(x: Variable[IntegerValue]) =
        createMoveGeneratorForMinimizationGoal(x)

    protected override def createMoveGeneratorForMinimizationGoal(x: Variable[IntegerValue]) = {
        val threadId = Thread.currentThread.getId
        val hotSpotIndicators =
            logger.withTimedLogScope("Creating hot-spot indicators for %s".format(x)) {
                createHotSpotIndicators(x)
            }
        val moveGenerators = new mutable.ArrayBuffer[MoveGenerator]
        for (constraint <- randomGenerator.shuffle(space.involvedConstraints(x).toSeq)) {
            val xs = constraint.inVariables.toIterator.filter(space.isSearchVariable).toSet
            if ((xs & variablesToIgnore).isEmpty) {
                val maybeMoveGenerator =
                    constraint.prepareForImplicitSolving(
                        space, randomGenerator, cfg.moveSizeDistribution,
                        createHotSpotDistribution(hotSpotIndicators), cfg.probabilityOfFairChoiceInPercent)
                if (maybeMoveGenerator.isDefined) {
                    variablesToIgnore ++= xs
                    logger.logg("Adding move generator for implicit constraint %s".format(constraint))
                    moveGenerators += maybeMoveGenerator.get
                    space.markAsImplied(constraint)
                }
            }
        }
        val remainingVariables = hotSpotIndicators.keys.toSet -- variablesToIgnore
        if (! remainingVariables.isEmpty) {
            val xs = remainingVariables.toIndexedSeq
            val hotSpotDistribution = createHotSpotDistribution(hotSpotIndicators)(xs).get
            logger.logg("Adding exchange generator on %s".format(xs))
            moveGenerators +=
                new RandomReassignmentGenerator(
                    space, xs, randomGenerator,
                    cfg.moveSizeDistribution, hotSpotDistribution, cfg.probabilityOfFairChoiceInPercent)
        }
        if (moveGenerators.size < 2) {
            moveGenerators.headOption
        } else {
            Some(new MoveGeneratorCollection(moveGenerators.toIndexedSeq, randomGenerator, null, 0))
        }
    }

    // Creates a hot-spot indicator for each search variable y involved in computing
    // the value of the given variable x.
    // A hot-spot indicator h(y) is a variable that reflects the influence of y on x.
    // (More precisely, the higher the influence of y's value on the value of x,
    // the higher the value of h(y).)
    // The resulting hot-spot indicators are for defining hot-spot aware move generators
    // via generator-specific distributions.
    // (As distributions can only handle integer frequencies, it does not make sense to
    // generalize this function to numerical values.)
    // To avoid runtime errors in move generation as well as local optima, we make sure that
    // h(y) >= 1 for all y at any time.
    // The code below deals with three cases:
    // 1. x is a search variable itself.
    //    (In FlatZinc practice x may be the end of the last task in a makespan minimization problem.)
    // 2. x is defined by a linear combination with non-negative terms.
    //    (In FlatZinc practice there are three uses for a top-level linear combination:
    //     1. To minimize hard constraint violation, the FlatZinc compiler uses a top-level
    //        linear combination that computes the violation of hard constraints.
    //     2. To minimize soft constraint violation, the model uses reification in combination
    //        with a top-level linear combination that computes the violation of soft constraints.
    //     3. To minimize resource usage, the model uses a linear combination to compute the
    //        resource consumption.)
    // 3. All other cases.
    //    (Maybe there is only a single soft constraint.)
    private def createHotSpotIndicators(x: AnyVariable): Map[AnyVariable, Variable[IntegerValue]] = {
        val zs = new mutable.HashMap[AnyVariable, mutable.ArrayBuffer[AX[IntegerValue]]]
        if (space.isSearchVariable(x)) {
            zs += x -> new mutable.ArrayBuffer[AX[IntegerValue]]
        }
        else if (space.isChannelVariable(x)) {
            for (y <- space.involvedSearchVariables(x)) {
                zs += y -> new mutable.ArrayBuffer[AX[IntegerValue]]
            }
            val constraint: yuck.core.Constraint = space.definingConstraint(x).get
            constraint match {
                case lc: LinearCombination[IntegerValue @ unchecked] =>
                    for (ax <- lc.axs
                         if ax.a.value >= 0 && IntegerValue.Traits.staticCast(ax.x.domain).maybeLb.exists(_.value >= 0))
                    {
                        for (y <- space.involvedSearchVariables(ax.x)) {
                            zs(y) += ax
                        }
                    }
                case sum: Sum[IntegerValue @ unchecked] =>
                    for (x <- sum.xs if IntegerValue.Traits.staticCast(x.domain).maybeLb.exists(_.value >= 0)) {
                        for (y <- space.involvedSearchVariables(x)) {
                            zs(y) += new AX(One, x)
                        }
                    }
                case _ =>
            }
        }
        val s = new mutable.HashMap[AnyVariable, Variable[IntegerValue]]
        val one = compileExpr[IntegerValue](IntConst(1))
        for (x <- zs.keys) {
            s += x -> createNonNegativeChannel[IntegerValue]
            zs(x) += new AX(One, one)
            val zl = AX.compact(zs(x))
            if (zl.forall(ax => ax.a == One)) {
               space.post(new Sum(nextConstraintId, null, zl.toIterator.map(ax => ax.x).toIndexedSeq, s(x)))
            } else {
               space.post(new LinearCombination(nextConstraintId, null, zl.toIndexedSeq, s(x)))
            }
        }
        s
    }

    private def createHotSpotDistribution
        (hotSpotIndicators: Map[AnyVariable, Variable[IntegerValue]])
        (xs: immutable.Seq[AnyVariable]):
        Option[Distribution] =
    {
        val hotSpotDistribution = DistributionFactory.createDistribution(xs.size)
        val weightedIndicators = xs.map(x => new AX(One, hotSpotIndicators(x)))
        space.post(new DistributionMaintainer(nextConstraintId, null, weightedIndicators, hotSpotDistribution))
        Some(hotSpotDistribution)
    }

}
