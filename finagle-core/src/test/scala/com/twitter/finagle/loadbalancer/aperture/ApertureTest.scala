package com.twitter.finagle.loadbalancer.aperture

import com.twitter.finagle._
import com.twitter.finagle.loadbalancer.{EndpointFactory, FailingEndpointFactory, NodeT}
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.util.Rng
import com.twitter.util.{Activity, Await, Duration, NullTimer}
import org.scalatest.FunSuite

class ApertureTest extends FunSuite with ApertureSuite {

  /**
   * A simple aperture balancer which doesn't have a controller or load metric
   * mixed in since we only want to test the aperture behavior exclusive of
   * these.
   *
   * This means that the aperture will not expand or contract automatically. Thus, each
   * test in this suite must manually adjust it or rely on the "rebuild" functionality
   * provided by [[Balancer]] which kicks in when we select a down node. Since aperture
   * uses P2C to select nodes, we inherit the same probabilistic properties that help
   * us avoid down nodes with the important caveat that we only select over a subset.
   */
  private class Bal extends TestBal {
    protected def statsReceiver = NullStatsReceiver
    protected class Node(val factory: EndpointFactory[Unit, Unit])
        extends ServiceFactoryProxy[Unit, Unit](factory)
        with NodeT[Unit, Unit]
        with ApertureNode {
      // We don't need a load metric since this test only focuses on
      // the internal behavior of aperture.
      def id: Int = 0
      def load: Double = 0
      def pending: Int = 0
      override val token: Int = 0
    }

    protected def newNode(factory: EndpointFactory[Unit, Unit]): Node =
      new Node(factory)

    protected def failingNode(cause: Throwable): Node =
      new Node(new FailingEndpointFactory[Unit, Unit](cause))
  }

  test("requires minAperture > 0") {
    intercept[IllegalArgumentException] {
      new ApertureLeastLoaded[Unit, Unit](
        endpoints = Activity.pending,
        smoothWin = Duration.Bottom,
        lowLoad = 0,
        highLoad = 0,
        minAperture = 0,
        maxEffort = 0,
        rng = Rng.threadLocal,
        statsReceiver = NullStatsReceiver,
        label = "",
        timer = new NullTimer,
        emptyException = new NoBrokersAvailableException,
        useDeterministicOrdering = None
      )
    }
  }

  test("minAperture <= vector.size") {
    val min = 100
    val bal = new Bal {
      override protected val minAperture = min
    }

    val counts = new Counts
    val vectorSize = min - 1
    bal.update(counts.range(vectorSize))

    // verify that we pick 2 within bounds
    bal.applyn(100)

    assert(bal.aperturex == vectorSize)
  }

  test("aperture <= vector.size") {
    val min = 100
    val bal = new Bal {
      override protected val minAperture = min
    }

    val counts = new Counts
    val vectorSize = min + 1
    bal.update(counts.range(vectorSize))
    assert(bal.aperturex == min)

    // increment by 100, should be bound by vector size
    bal.adjustx(100)
    assert(bal.aperturex == vectorSize)
  }

  test("Empty vectors") {
    val bal = new Bal
    intercept[Empty] { Await.result(bal.apply()) }

    // transient update
    val counts = new Counts
    bal.update(counts.range(5))
    bal.applyn(100)
    assert(counts.nonzero.size > 0)

    // go back to zero
    bal.update(Vector.empty)
    intercept[Empty] { Await.result(bal.apply()) }
  }

  test("Balance only within the aperture") {
    val counts = new Counts
    val bal = new Bal
    bal.update(counts.range(10))
    assert(bal.maxUnitsx == 10)
    bal.applyn(100)
    assert(counts.nonzero.size == 1)

    bal.adjustx(1)
    bal.applyn(100)
    assert(counts.nonzero.size == 2)

    counts.clear()
    bal.adjustx(-1)
    bal.applyn(100)
    assert(counts.nonzero.size == 1)
  }

  test("min aperture size is not > the number of active nodes") {
    val counts = new Counts
    val bal = new Bal {
      override protected val minAperture = 4
    }

    bal.update(counts.range(10))

    // Sanity check custom minAperture enforced.
    bal.adjustx(-100)
    bal.applyn(1000)
    assert(counts.nonzero.size == 4)

    // Now close 8
    counts.clear()
    counts.take(8).foreach(_.status = Status.Closed)
    bal.update(counts.range(10))
    bal.applyn(1000)
    assert(counts.nonzero.size == 2)
  }

  test("Don't operate outside of aperture range") {
    val counts = new Counts
    val bal = new Bal

    bal.update(counts.range(10))
    bal.adjustx(10000)
    bal.applyn(1000)
    assert(counts.nonzero.size == 10)

    counts.clear()
    bal.adjustx(-100000)
    bal.applyn(1000)
    assert(counts.nonzero.size == 1)
  }

  test("Avoid unavailable hosts") {
    val counts = new Counts
    val bal = new Bal

    bal.update(counts.range(10))
    bal.adjustx(3)
    bal.applyn(100)
    assert(counts.nonzero.size == 4)

    // Since tokens are assigned, we don't know apriori what's in the
    // aperture*, so figure it out by observation.
    //
    // *Ok, technically we can, since we're using deterministic
    // randomness.
    for (unavailableStatus <- List(Status.Closed, Status.Busy)) {
      val nonZeroKeys = counts.nonzero
      val closed0 = counts(nonZeroKeys.head)
      val closed1 = counts(nonZeroKeys.tail.head)

      closed0.status = unavailableStatus
      closed1.status = unavailableStatus

      val closed0Req = closed0.total
      val closed1Req = closed1.total

      bal.applyn(100)

      // We want to make sure that we haven't sent requests to the
      // `Closed` nodes since our aperture is wide enough to avoid
      // them.
      assert(closed0Req == closed0.total)
      assert(closed1Req == closed1.total)
    }
  }

  test("Nonavailable vectors") {
    val counts = new Counts
    val bal = new Bal

    bal.update(counts.range(10))
    for (f <- counts)
      f.status = Status.Closed

    bal.applyn(1000)
    assert(bal.aperturex == 1)
    // since our status sort is stable, we know that
    // even though we rebuild, we will still only be
    // sending load to the head.
    assert(counts.nonzero.size == 1)

    val goodkey = 0
    counts(goodkey).status = Status.Open
    counts.clear()
    bal.applyn(1000)
    assert(counts.nonzero == Set(goodkey))
  }

  test("useDeterministicOrdering, clients evenly divide servers") {
    val counts = new Counts
    val bal = new Bal {
      override protected val useDeterministicOrdering = Some(true)
    }

    ProcessCoordinate.setCoordinate(offset = 0, instanceId = 1, totalInstances = 10)
    bal.update(counts.range(10))
    bal.applyn(1000)
    assert(counts.nonzero == Set(1, 2, 3, 4))
  }

  test("useDeterministicOrdering, clients unevenly divide servers") {
    val counts = new Counts
    val bal = new Bal {
      override protected val useDeterministicOrdering = Some(true)
    }

    ProcessCoordinate.setCoordinate(offset = 0, instanceId = 1, totalInstances = 4)
    bal.update(counts.range(10))
    assert(bal.minUnitsx == 4)
    bal.applyn(1000)
    // The range is 2.5, so we need a physical aperture of at least 2 to satisfy
    // the `minUnits` of 4. In this case, a physical aperture of 2 maps to 6 servers
    // on the ring, where 2 and 7 get ~1/2 the traffic relative to the rest of
    // the nodes.
    assert(counts.nonzero == Set(2, 3, 4, 5, 6, 7))
    assert(counts(2).total.toDouble / counts(3).total - 0.5 <= 0.1)
    assert(counts(7).total.toDouble / counts(6).total - 0.5 <= 0.1)
  }

  test("no-arg rebuilds are idempotent") {
    val bal = new Bal {
      override protected val useDeterministicOrdering = Some(true)
    }

    ProcessCoordinate.setCoordinate(0, 5, 10)

    val servers = Vector.tabulate(10)(Factory)
    bal.update(servers)

    val order = bal.distx.vector
    for (_ <- 0 to 100) {
      bal.rebuildx()
      assert(order.indices.forall { i =>
        order(i) == bal.distx.vector(i)
      })
    }
  }

  test("order maintained when status flaps") {
    val bal = new Bal {
      override protected val useDeterministicOrdering = Some(true)
    }

    ProcessCoordinate.unsetCoordinate()

    val servers = Vector.tabulate(5)(Factory)
    bal.update(servers)

    // 3 of 5 servers are in the aperture
    bal.adjustx(2)
    assert(bal.aperturex == 3)

    ProcessCoordinate.setCoordinate(offset = 0, instanceId = 3, totalInstances = 5)

    // We just happen to know that based on our ordering, instance 2 is in the aperture.
    // Note, we have an aperture of 3 and 1 down, so the probability of picking the down
    // node with p2c is ((1/3)^2)^maxEffort . Instead of attempting to hit this case, we
    // force a rebuild artificially.
    servers(2).status = Status.Busy
    bal.rebuildx()
    for (i <- servers.indices) {
      assert(servers(i) == bal.distx.vector(i).factory)
    }

    // flip back status
    servers(2).status = Status.Open
    bal.rebuildx()
    for (i <- servers.indices) {
      assert(servers(i) == bal.distx.vector(i).factory)
    }
  }
}
