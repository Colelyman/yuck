package yuck.flatzinc.runner

import java.util.logging._

import scala.math._

import yuck.annealing._
import yuck.core._
import yuck.flatzinc.FlatZincSolverConfiguration
import yuck.flatzinc.ast.FlatZincAST
import yuck.flatzinc.compiler.{FlatZincCompiler, FlatZincCompilerResult}
import yuck.util.logging.LazyLogger

/**
 * @author Michael Marte
 *
 */
final class FlatZincSolverGenerator
    (ast: FlatZincAST, cfg: FlatZincSolverConfiguration, logger: LazyLogger, monitor: AnnealingMonitor)
    extends SolverGenerator
{

    override def solverName = "FZS"

    private final class SolverForProblemWithoutStrategy
        (override val name: String, compilerResult: FlatZincCompilerResult)
        extends Solver
        with StandardSolverInterruptionSupport
    {
        require(compilerResult.strategy == null)
        private val space = compilerResult.space
        private val objective = compilerResult.objective
        private var finished = false
        space.initialize
        override def hasFinished = finished
        override def call = {
            require(! finished)
            val result = new Result(name, space, objective, compilerResult)
            result.bestProposal = space.searchState
            result.costsOfBestProposal = objective.costs(result.bestProposal)
            finished = true
            result
        }
    }

    private final class BaseSolverGenerator
        (randomGenerator: RandomGenerator, solverIndex: Int)
        extends SolverGenerator
    {
        override def solverName = "FZS-%d".format(solverIndex)
        override def call = {
            val compiler = new FlatZincCompiler(ast, cfg, randomGenerator.nextGen, logger)
            val compilerResult = compiler.call
            val space = compilerResult.space
            // The initializer will respect existing value assignments.
            val initializer = new RandomInitializer(space, randomGenerator.nextGen)
            initializer.run
            if (compilerResult.strategy == null) {
                new SolverForProblemWithoutStrategy(solverName, compilerResult)
            } else {
                val schedule = new StandardAnnealingScheduleFactory(space.searchVariables.size, randomGenerator.nextGen).call
                new SimulatedAnnealing(
                    solverName,
                    space,
                    schedule,
                    compilerResult.strategy,
                    randomGenerator.nextGen,
                    compilerResult.objective,
                    cfg.maybeRoundLimit,
                    monitor,
                    compilerResult,
                    cfg.checkConstraintPropagation)
            }
        }
    }

    override def call = {
        val randomGenerator = new JavaRandomGenerator(cfg.seed)
        var solver: Solver = null
        if (cfg.restartLimit == 1) {
            solver = new BaseSolverGenerator(randomGenerator.nextGen, 1).call
        } else {
            val solvers =
                for (i <- 1 to max(1, cfg.restartLimit)) yield
                    new OnDemandGeneratedSolver(
                        new BaseSolverGenerator(randomGenerator.nextGen, i),
                        logger)
            val numberOfThreads = cfg.numberOfVirtualCores match {
                case 4 | 8 => 4 // use 4 threads on both i5 and i7, also avoids fan noise on i7
                case n => n
            }
            solver = new ParallelSolver(solvers, numberOfThreads, solverName, logger)
        }
        if (cfg.maybeRuntimeLimitInSeconds.isDefined &&
            (cfg.maybeRoundLimit.isEmpty || cfg.maybeRoundLimit.get > 0) &&
            ! solver.hasFinished)
        {
            solver = new TimeboxedSolver(solver, cfg.maybeRuntimeLimitInSeconds.get, logger)
        }
        solver
    }

}
