# Yuck

## What is Yuck?

* Yuck is a constraint solver powered by local search.
* Yuck's approach to problem solving is comparable to Comet [HM05] and [OscaR](http://oscarlib.bitbucket.org)/CBLS [BMFP15].
* Yuck has not been designed to compete with highly tuned LP, CP, or SAT solvers, but for cases where the use of such solvers is inappropriate or seems risky, namely when the input data might be inconsistent with the constraints (so the solver is expected to build around what is given), or when the requirements are hard to model and it seems easier to provide domain-specific constraints for local search than for tree search.
* Yuck can be used as a library or as a [FlatZinc](http://www.minizinc.org/downloads/doc-1.6/flatzinc-spec.pdf) interpreter that integrates with the [MiniZinc IDE](http://www.minizinc.org/ide/index.html).
* Yuck is written in Scala and exploits the Scala library's immutable collection classes for implementing global constraints.
* Yuck is provided under the terms of the [Mozilla Public License 2.0](https://www.mozilla.org/en-US/MPL/2.0).

## Current state

* Yuck implements simulated annealing along with some basic annealing schedules and some schedule combinators.
* Yuck supports lexicographic cost functions with both minimization and maximization goals.
* Yuck allows to timebox and parallelize solvers by means of solver combinators.
* Yuck supports the interruption and the resumption of solvers to facilitate the presentation of intermediate results.
* Yuck supports boolean, integer, and integer set variables.
* Yuck's constraint library is far from complete but already provides the most frequently used constraints.
* Yuck's FlatZinc frontend supports most of FlatZinc and many global MiniZinc constraints, see [FlatZinc support](#flatzinc-support).
* Yuck is developer-friendly and easy to extend, see [Contributing](#contributing).
* Yuck was submitted to the [2016 MiniZinc challenge](http://www.minizinc.org/challenge2016/challenge.html) where it coped quite well with 12 out of 20 problems.

## FlatZinc support

Yuck's FlatZinc frontend supports all of FlatZinc except for float variables and float constraints.

Yuck provides dedicated solvers for the following global MiniZinc constraints:

* all_different, all_different_except_0
* at_least, at_most
* bin_packing, bin_packing_capa, bin_packing_load
* count_eq, count_geq, count_gt, count_leq, count_lt, count_neq
* cumulative
* disjunctive
* exactly
* inverse
* lex_less, lex_lesseq
* maximum
* member
* minimum
* nvalue
* regular
* table

For the following global MiniZinc constraints, the standard decompositions are suitable for local search:

* all_equal
* decreasing
* element
* increasing
* global_cardinality, global_cardinality_closed
* knapsack
* network_flow, network_flow_cost

When used as a FlatZinc interpreter, Yuck proceeds as follows:

* It performs a limited amount of constraint propagation to reduce variable domains before starting local search.
* It eliminates variables by exploiting equality constraints.
* It identifies and exploits functional dependencies to reduce the number of decision variables.
* It uses an annealing schedule that interleaves adaptive cooling with geometric reheating.
* In move generation, it concentrates on variables that are involved in constraint violations.
* It uses restarting to increase robustness: When a solver terminates without having reached the its objective, it gets replaced by a new one starting out from another random assignment.
* When Yuck is configured to use multiple threads, restarting turns into parallel solving: Given a thread pool and a stream of solvers with a common objective, Yuck submits the solvers to the thread pool and, when one of the solvers provides a solution that satisfies the objective, Yuck discards all running and pending solvers.

## Installation and MiniZinc IDE integration on Debian-based systems

For Debian based systems, this is the latest Yuck package: [yuck_20161018_all.deb](https://drive.google.com/open?id=0B3cKM2FQLv9vWW5LZEZUWU1HRG8)

This package has two dependencies: It needs bash and a Java 6 (or higher) runtime environment.

To configure Yuck as a backend for the [MiniZinc IDE](http://www.minizinc.org/ide/index.html), proceed as follows:

* In the MiniZinc IDE, go the **Configuration** tab.
* From the **Solver** dropdown list, select **Add new solver** and a
  window will open.
* In that window, enter the following values:
  * Name: Yuck
  * Executable: `/usr/bin/yuck`
  * MiniZinc library path: `-I/usr/share/yuck/mzn`
* Then press the **Add** button.
* Back on the **Configuration** tab, select **Yuck** from the dropdown list.
* If your hardware allows, increase the number of threads.

## Future work

* Allow for the reification of all global constraints implemented by Yuck
* Allow for the composition of lexicographic cost functions in
  MiniZinc through constraint and goal annotations
* Implement tabu search
* Implement among, diffn, circuit, subcircuit, ...
* Add support for float variables
* Refactor and improve up-front propagation
* Reduce dependence on integration testing by adding more unit tests

## Contributing

Yuck is developer-friendly:

* It's source code is clean and well-documented.
* It provides extensive and configurable logging.
* Test coverage is good.

Yuck is easy to extend:

* It's core machinery can be asked to look for misbehaving constraints while running a solver.
* New variable types can be added without touching the core machinery and without impairing the type safety of the system.

To build and run Yuck, you need [sbt](http://www.scala-sbt.org).

Use `sbt eclipse` to create an Eclipse project.

### Building

To build and rebuild Yuck and its documentation, use the sbt standard targets:

* `sbt compile` compiles all sources that need compilation.
* `sbt doc` creates the ScalaDoc documentation from the sources.
* `sbt clean` removes all artifacts of compiling and building.

### Testing

Yuck tests are based on [JUnit 4](http://junit.org/junit4/) and
[MiniZinc 2.0.x](http://www.minizinc.org/software.html).

* `make unit-tests` builds and runs all unit tests.
* `make minizinc-tests` runs all FlatZinc frontend tests.
* `make minizinc-examples` exercises Yuck on a subset of the MiniZinc 1.6 examples.
* `make tests` runs all tests.

FlatZinc generation is not fully automated; to generate the FlatZinc
models, execute the following commands in your Yuck folder:

```
pushd resources/mzn/tests/; ./makemodels; popd
pushd resources/mzn/examples/; ./makemodels; popd
```

All test cases other than unit tests leave a log in the local ```tmp```
folder.

Optimization results undergo automated verification on the basis of
the G12 solver (which comes with MiniZinc).

### Running

There are three ways to run Yuck:

* Stage and run it:

  ```
   make stage
   ./bin/yuck --help
   ```

* Run it through sbt:

  ```
  sbt run --help
  ```

* Create and install a Debian package:

  ```
  make deb
  sudo dpkg -i target/yuck_$(date +%Y%m%d)_all.deb
  /usr/bin/yuck --help
  ```

### Coding style

Yuck code should follow the [Scala Style
Guide](http://docs.scala-lang.org/style/) with two exceptions:

  * Indentation should be four instead of two spaces.
  * Method calls should always be chained with the dot operator, so don't use infix notation.

In addition, the following rules apply:

  * Lines should not be much longer than 120 characters.
  * Don't give a result type when overriding a definition.

# References

[BMFP15] G. Björdal, J.-N. Monette, P. Flener, and J. Pearson. A Constraint-Based Local Search Backend for MiniZinc. Constraints, 20(3):325-345, 2015. 

[HM05] P. V. Hentenryck and L. Michel. Constraint-Based Local Search. MIT Press, 2005.
