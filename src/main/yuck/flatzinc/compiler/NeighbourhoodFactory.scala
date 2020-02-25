package yuck.flatzinc.compiler

import scala.collection._

import yuck.constraints.{Alldistinct, BinaryConstraint, OptimizationGoalTracker, SatisfactionGoalTracker}
import yuck.core._
import yuck.flatzinc.ast.{Maximize, Minimize}

/**
 * Customizable factory for creating a neighbourhood for the problem at hand.
 *
 * Candidates for implicit solving are handled with priority to avoid that their variables
 * get included into other neighbourhoods. (In case two candidates for implicit solving
 * compete for a variable, this conflict is resolved by random choice.)
 *
 * The default implementation for satisfaction goals creates a
 * [[yuck.core.RandomReassignmentGenerator RandomReassignmentGenerator]] instance
 * with a focus on search variables that are involved in constraint violations.
 * (The guidance is provided by an instance of [[yuck.constraints.SatisfactionGoalTracker
 * SatisfactionGoalTracker]]).
 * This behaviour can be customized by overloading createNeighbourhoodForSatisfactionGoal.
 *
 * The default implementation for optimization goals creates an unbiased
 * [[yuck.core.RandomReassignmentGenerator RandomReassignmentGenerator]] instance
 * on the involved search variables.
 * This behaviour can be customized by overloading
 * createNeighbourhoodFor{Minimization, Maximization}Goal.
 *
 * In case we end up with two neighbourhoods (one for the satisfaction goal and another
 * for the optimization goal), these neighbourhoods will be stacked by creating an instance
 * of [[yuck.core.NeighbourhoodCollection NeighbourhoodCollection]] instrumented to
 * focus on the satisfaction goal in case hard constraints are violated. To this end,
 * we keep track of goal satisfaction by means of a dynamic distribution (maintained by
 * an instance of [[yuck.constraints.OptimizationGoalTracker OptimizationGoalTracker]]).
 *
 * In an attempt to decouple this factory from implementation details of data structures
 * (hash sets, in particular) and the earlier compiler stages, we sort constraints and
 * variables (by id) before further processing.
 *
 * @author Michael Marte
 */
class NeighbourhoodFactory
    (cc: CompilationContext, randomGenerator: RandomGenerator)
    extends CompilationPhase(cc, randomGenerator)
{
    protected val cfg = cc.cfg
    protected val logger = cc.logger
    protected val ast = cc.ast
    protected val space = cc.space
    protected val implicitlyConstrainedVars = cc.implicitlyConstrainedVars

    override def run = {
        cc.maybeNeighbourhood = createNeighbourhood
    }

    import HighPriorityImplicits._

    private final def createNeighbourhood: Option[Neighbourhood] = {
        val maybeNeighbourhood0 =
            logger.withTimedLogScope("Creating a neighbourhood for solving hard constraints") {
                createNeighbourhoodForSatisfactionGoal(cc.costVar)
            }
        val maybeNeighbourhood1 =
            cc.ast.solveGoal match {
                case Minimize(a, _) =>
                    val maybeNeighbourhood1 =
                        logger.withTimedLogScope("Creating a neighbourhood for minimizing %s".format(a)) {
                            createNeighbourhoodForMinimizationGoal[IntegerValue](a)
                        }
                    stackNeighbourhoods(cc.costVar, maybeNeighbourhood0, a, maybeNeighbourhood1)
                case Maximize(a, _) =>
                    val maybeNeighbourhood1 =
                        logger.withTimedLogScope("Creating a neighbourhood for maximizing %s".format(a)) {
                            createNeighbourhoodForMaximizationGoal[IntegerValue](a)
                        }
                    stackNeighbourhoods(cc.costVar, maybeNeighbourhood0, a, maybeNeighbourhood1)
                case _ =>
                    maybeNeighbourhood0
            }
        maybeNeighbourhood1
    }

    protected def createNeighbourhoodForSatisfactionGoal(x: BooleanVariable): Option[Neighbourhood] = {
        require(x == cc.costVar)
        val levelCfg = cfg.level0Configuration
        space.registerObjectiveVariable(x)
        val neighbourhoods = new mutable.ArrayBuffer[Neighbourhood]
        val candidatesForImplicitSolving =
            if (cfg.useImplicitSolving) {
                space.involvedConstraints(x).iterator.filter(_.isCandidateForImplicitSolving(space)).toBuffer.sorted
            } else {
                Nil
            }
        for (constraint <- randomGenerator.shuffle(candidatesForImplicitSolving)) {
            val xs = constraint.inVariables.toSet
            if ((xs & implicitlyConstrainedVars).isEmpty) {
                val maybeNeighbourhood =
                    constraint.prepareForImplicitSolving(
                        space, randomGenerator, cfg.moveSizeDistribution, _ => None, levelCfg.maybeFairVariableChoiceRate)
                if (maybeNeighbourhood.isDefined) {
                    implicitlyConstrainedVars ++= xs
                    space.markAsImplicit(constraint)
                    logger.logg("Adding a neighbourhood for implicit constraint %s".format(constraint))
                    neighbourhoods += maybeNeighbourhood.get
                }
            }
        }
        val searchVars =
            space.involvedSearchVariables(x).diff(implicitlyConstrainedVars).toBuffer.sorted.toIndexedSeq
        if (! searchVars.isEmpty) {
            for (x <- searchVars if ! x.domain.isFinite) {
                throw new VariableWithInfiniteDomainException(x)
            }
            if (levelCfg.guideOptimization) {
                val searchVarIndex = searchVars.iterator.zipWithIndex.toMap
                val costVars = cc.costVars.toIndexedSeq
                val hotSpotDistribution = DistributionFactory.createDistribution(searchVars.size)
                def involvedSearchVars(x: AnyVariable) =
                    space.involvedSearchVariables(x).diff(implicitlyConstrainedVars).iterator
                        .map(searchVarIndex).toIndexedSeq
                val involvementMatrix = costVars.iterator.map(x => (x, involvedSearchVars(x))).toMap
                space.post(
                    new SatisfactionGoalTracker(space.nextConstraintId, None, involvementMatrix, hotSpotDistribution))
                logger.logg("Adding a neighbourhood over %s".format(searchVars))
                neighbourhoods +=
                    new RandomReassignmentGenerator(
                        space, searchVars, randomGenerator,
                        cfg.moveSizeDistribution, Some(hotSpotDistribution), levelCfg.maybeFairVariableChoiceRate)
            } else {
                createNeighbourhoodOnInvolvedSearchVariables(x)
            }
        }
        if (neighbourhoods.size < 2) {
            neighbourhoods.headOption
        } else {
            Some(new NeighbourhoodCollection(neighbourhoods.toIndexedSeq, randomGenerator, None, None))
        }
    }

    protected def createNeighbourhoodForMinimizationGoal
        [Value <: NumericalValue[Value]]
        (x: NumericalVariable[Value])
        (implicit valueTraits: NumericalValueTraits[Value]):
        Option[Neighbourhood] =
    {
        val dx = x.domain
        if (space.isDanglingVariable(x) && dx.hasLb) {
            val a = dx.lb
            logger.logg("Assigning %s to dangling objective variable %s".format(a, x))
            space.setValue(x, a)
            None
        } else {
            createNeighbourhoodOnInvolvedSearchVariables(x)
        }
    }

    protected def createNeighbourhoodForMaximizationGoal
        [Value <: NumericalValue[Value]]
        (x: NumericalVariable[Value])
        (implicit valueTraits: NumericalValueTraits[Value]):
        Option[Neighbourhood] =
    {
        val dx = x.domain
        if (space.isDanglingVariable(x) && dx.hasUb) {
            val a = dx.ub
            logger.logg("Assigning %s to dangling objective variable %s".format(a, x))
            space.setValue(x, a)
            None
        } else {
            createNeighbourhoodOnInvolvedSearchVariables(x)
        }
    }

    protected final def createNeighbourhoodOnInvolvedSearchVariables(x: AnyVariable): Option[Neighbourhood] = {
        space.registerObjectiveVariable(x)
        val xs =
            (if (space.isSearchVariable(x)) Set(x) else space.involvedSearchVariables(x)).diff(implicitlyConstrainedVars)
        if (xs.isEmpty) {
            None
        } else {
            logger.logg("Adding a neighbourhood over %s".format(xs))
            Some(new RandomReassignmentGenerator(
                space, xs.toBuffer.sorted.toIndexedSeq, randomGenerator, cfg.moveSizeDistribution, None, None))
        }
    }

    private final class Level
        [Value <: NumericalValue[Value]]
        (val costs: NumericalVariable[Value], val objective: AnyObjective)
    {
        val weight = createNonNegativeChannel[IntegerValue]
        val effect = new ReusableMoveEffectWithFixedVariable(weight)
        override def toString = (costs, weight).toString
    }

    private final class LevelWeightMaintainer
        (id: Id[yuck.core.Constraint], level0: Level[BooleanValue], level1: Level[IntegerValue])
        extends Constraint(id)
    {
        override def inVariables = List(level0.costs, level1.costs)
        override def outVariables = List(level0.weight, level1.weight)
        private val effects = List(level0.effect, level1.effect)
        override def toString = "levelWeightMaintainer(%s, %s)".format(level0, level1)
        override def initialize(now: SearchState) = {
            val solved = level0.objective.isGoodEnough(now.value(level0.costs))
            level0.effect.a = if (solved) Zero else One
            level1.effect.a = if (! solved || level1.objective.isGoodEnough(now.value(level1.costs))) Zero else One
            effects
        }
        override def consult(before: SearchState, after: SearchState, move: Move) =
            initialize(after)
        override def commit(before: SearchState, after: SearchState, move: Move) =
            effects
    }

    private def stackNeighbourhoods(
        costs0: BooleanVariable, maybeNeighbourhood0: Option[Neighbourhood],
        costs1: IntegerVariable, maybeNeighbourhood1: Option[Neighbourhood]):
        Option[Neighbourhood] =
    {
        (maybeNeighbourhood0, maybeNeighbourhood1) match {
            case (None, None) => None
            case (Some(neighbourhood0), None) => Some(neighbourhood0)
            case (None, Some(neighbourhood1)) => Some(neighbourhood1)
            case (Some(neighbourhood0), Some(neighbourhood1)) =>
                val List(objective0, objective1) = cc.objective.asInstanceOf[HierarchicalObjective].objectives
                val level0 = new Level(costs0, objective0)
                val level1 = new Level(costs1, objective1)
                space.post(new LevelWeightMaintainer(nextConstraintId, level0, level1))
                val hotSpotDistribution = new ArrayBackedDistribution(2)
                space.post(
                    new OptimizationGoalTracker(
                        nextConstraintId, null,
                        OptimizationMode.Min, List(level0.weight, level1.weight).toIndexedSeq, hotSpotDistribution))
                Some(new NeighbourhoodCollection(
                        Vector(neighbourhood0, neighbourhood1), randomGenerator, Some(hotSpotDistribution), None))
        }
    }

}