package yuck.core.test

import scala.collection._

import org.junit._

import yuck.core._
import yuck.util.testing.UnitTest

/**
 * @author Michael Marte
 *
 */
@Test
@FixMethodOrder(runners.MethodSorters.NAME_ASCENDING)
class RandomGeneratorTest extends UnitTest {

    private def testShuffling(shuffle: (RandomGenerator, IndexedSeq[Int]) => IndexedSeq[Int]): Unit = {
        val N = 1000
        val M = 10
        var data: IndexedSeq[Int] = mutable.ArrayBuffer.tabulate(M)(identity)
        val randomGenerator = new JavaRandomGenerator
        val numberOfChangedPositionsDistribution = new Array[Int](M + 1)
        for (i <- 1 to N) {
            val shuffledData = shuffle(randomGenerator, data)
            var numberOfChangedPositions = 0
            for (j <- 0 until M) {
                if (shuffledData(j) != data(j)) numberOfChangedPositions += 1
            }
            numberOfChangedPositionsDistribution(numberOfChangedPositions) += 1
            data = shuffledData
        }
        assertEq(numberOfChangedPositionsDistribution(0), 0)
        assertGt(numberOfChangedPositionsDistribution(6), 10)
        assertGt(numberOfChangedPositionsDistribution(7), 50)
        assertGt(numberOfChangedPositionsDistribution(8), 150)
        assertGt(numberOfChangedPositionsDistribution(9), 350)
        assertGt(numberOfChangedPositionsDistribution(10), 350)
    }

    @Test
    def testEagerShuffling {
        testShuffling(
            (randomGenerator, data) => randomGenerator.shuffle[Int, IndexedSeq, mutable.ArrayBuffer](data))
    }

    @Test
    def testLazyShuffling {
        testShuffling(
            (randomGenerator, data) => {
                val shuffledData = new mutable.ArrayBuffer[Int]
                shuffledData ++= randomGenerator.lazyShuffle(data)
                shuffledData
            })
    }

}
