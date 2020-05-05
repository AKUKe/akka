/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import akka.Done
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Props
import akka.cluster.Cluster
import akka.cluster.MemberStatus
import akka.cluster.sharding.ShardRegion.EntityId
import akka.cluster.sharding.ShardRegion.ShardId
import akka.cluster.sharding.internal.RememberEntitiesCoordinatorStore
import akka.cluster.sharding.internal.RememberEntitiesShardStore
import akka.cluster.sharding.internal.RememberEntitiesProvider
import akka.testkit.AkkaSpec
import akka.testkit.TestException
import akka.testkit.TestProbe
import akka.testkit.WithLogCapturing
import com.github.ghik.silencer.silent
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

object RememberEntitiesFailureSpec {
  val config = ConfigFactory.parseString(s"""
      akka.loglevel = DEBUG
      akka.loggers = ["akka.testkit.SilenceAllTestEventListener"]
      akka.actor.provider = cluster
      akka.remote.artery.canonical.port = 0
      akka.remote.classic.netty.tcp.port = 0
      akka.cluster.sharding.distributed-data.durable.keys = []
      akka.cluster.sharding.state-store-mode = custom
      akka.cluster.sharding.custom-store = "akka.cluster.sharding.RememberEntitiesFailureSpec$$FakeStore"
      # quick backoffs
      akka.cluster.sharding.entity-restart-backoff = 1s
      akka.cluster.sharding.shard-failure-backoff = 1s
      akka.cluster.sharding.coordinator-failure-backoff = 1s
      akka.cluster.sharding.updating-state-timeout = 1s
    """)

  class EntityActor extends Actor with ActorLogging {
    override def receive: Receive = {
      case "stop" =>
        log.info("Stopping myself!")
        context.stop(self)
      case "graceful-stop" =>
        context.parent ! ShardRegion.Passivate("stop")
      case msg => sender() ! msg
    }
  }

  case class EntityEnvelope(entityId: Int, msg: Any)

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case EntityEnvelope(id, payload) => (id.toString, payload)
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case EntityEnvelope(id, _) => (id % 10).toString
  }

  sealed trait Fail
  case object NoResponse extends Fail
  case object CrashStore extends Fail
  case object StopStore extends Fail

  // outside store since we need to be able to set them before sharding initializes
  @volatile var failShardGetEntities = Map.empty[ShardId, Fail]
  @volatile var failCoordinatorGetShards: Option[Fail] = None

  case class ShardStoreCreated(store: ActorRef, shardId: ShardId)
  case class CoordinatorStoreCreated(store: ActorRef)

  @silent("never used")
  class FakeStore(settings: ClusterShardingSettings, typeName: String) extends RememberEntitiesProvider {
    override def shardStoreProps(shardId: ShardId): Props = FakeShardStoreActor.props(shardId)
    override def coordinatorStoreProps(): Props = FakeCoordinatorStoreActor.props()
  }

  object FakeShardStoreActor {
    def props(shardId: ShardId): Props = Props(new FakeShardStoreActor(shardId))

    case class FailAddEntity(entityId: EntityId, whichWay: Fail)
    case class FailRemoveEntity(entityId: EntityId, whichWay: Fail)
    case class ClearFail(entityId: EntityId)
  }
  class FakeShardStoreActor(shardId: ShardId) extends Actor with ActorLogging {
    import FakeShardStoreActor._

    implicit val ec = context.system.dispatcher
    private var failAddEntity = Map.empty[EntityId, Fail]
    private var failRemoveEntity = Map.empty[EntityId, Fail]

    context.system.eventStream.publish(ShardStoreCreated(self, shardId))

    override def receive: Receive = {
      case RememberEntitiesShardStore.GetEntities =>
        failShardGetEntities.get(shardId) match {
          case None             => sender ! RememberEntitiesShardStore.RememberedEntities(Set.empty)
          case Some(NoResponse) => log.debug("Sending no response for GetEntities")
          case Some(CrashStore) => throw TestException("store crash on GetEntities")
          case Some(StopStore)  => context.stop(self)
        }
      case RememberEntitiesShardStore.AddEntity(entityId) =>
        failAddEntity.get(entityId) match {
          case None             => sender ! RememberEntitiesShardStore.UpdateDone(entityId)
          case Some(NoResponse) => log.debug("Sending no response for AddEntity")
          case Some(CrashStore) => throw TestException("store crash on AddEntity")
          case Some(StopStore)  => context.stop(self)
        }
      case RememberEntitiesShardStore.RemoveEntity(entityId) =>
        failRemoveEntity.get(entityId) match {
          case None             => sender ! RememberEntitiesShardStore.UpdateDone(entityId)
          case Some(NoResponse) => log.debug("Sending no response for RemoveEntity")
          case Some(CrashStore) => throw TestException("store crash on AddEntity")
          case Some(StopStore)  => context.stop(self)
        }
      case FailAddEntity(id, whichWay) =>
        failAddEntity = failAddEntity.updated(id, whichWay)
        sender() ! Done
      case FailRemoveEntity(id, whichWay) =>
        failRemoveEntity = failRemoveEntity.updated(id, whichWay)
        sender() ! Done
      case ClearFail(id) =>
        failAddEntity = failAddEntity - id
        failRemoveEntity = failRemoveEntity - id
        sender() ! Done
    }
  }

  object FakeCoordinatorStoreActor {
    def props(): Props = Props(new FakeCoordinatorStoreActor)

    case class FailAddShard(shardId: ShardId, wayToFail: Fail)
    case class ClearFailShard(shardId: ShardId)
  }
  class FakeCoordinatorStoreActor extends Actor with ActorLogging {
    import FakeCoordinatorStoreActor._

    context.system.eventStream.publish(CoordinatorStoreCreated(context.self))

    private var failAddShard = Map.empty[ShardId, Fail]

    override def receive: Receive = {
      case RememberEntitiesCoordinatorStore.GetShards =>
        failCoordinatorGetShards match {
          case None             => sender() ! RememberEntitiesCoordinatorStore.RememberedShards(Set.empty)
          case Some(NoResponse) =>
          case Some(CrashStore) => throw TestException("store crash on load")
          case Some(StopStore)  => context.stop(self)
        }
      case RememberEntitiesCoordinatorStore.AddShard(shardId) =>
        failAddShard.get(shardId) match {
          case None             => sender() ! RememberEntitiesCoordinatorStore.UpdateDone(shardId)
          case Some(NoResponse) =>
          case Some(CrashStore) => throw TestException("store crash on add")
          case Some(StopStore)  => context.stop(self)
        }
      case FailAddShard(shardId, wayToFail) =>
        log.debug("Failing store of {} with {}", shardId, wayToFail)
        failAddShard = failAddShard.updated(shardId, wayToFail)
        sender() ! Done
      case ClearFailShard(shardId) =>
        log.debug("No longer failing store of {}", shardId)
        failAddShard = failAddShard - shardId
        sender() ! Done
    }
  }

}

class RememberEntitiesFailureSpec
    extends AkkaSpec(RememberEntitiesFailureSpec.config)
    with AnyWordSpecLike
    with WithLogCapturing {

  import RememberEntitiesFailureSpec._

  override def atStartup(): Unit = {
    // Form a one node cluster
    val cluster = Cluster(system)
    cluster.join(cluster.selfAddress)
    awaitAssert(cluster.readView.members.count(_.status == MemberStatus.Up) should ===(1))
  }

  "Remember entities handling in sharding" must {

    List(NoResponse, CrashStore, StopStore).foreach { wayToFail: Fail =>
      s"recover when initial remember entities load fails $wayToFail" in {
        log.debug("Getting entities for shard 1 will fail")
        failShardGetEntities = Map("1" -> wayToFail)

        try {
          val probe = TestProbe()
          val sharding = ClusterSharding(system).start(
            s"initial-$wayToFail",
            Props[EntityActor],
            ClusterShardingSettings(system).withRememberEntities(true),
            extractEntityId,
            extractShardId)

          sharding.tell(EntityEnvelope(1, "hello-1"), probe.ref)
          probe.expectNoMessage() // message is lost because shard crashes

          log.debug("Resetting initial fail")
          failShardGetEntities = Map.empty

          // shard should be restarted and eventually succeed
          awaitAssert {
            sharding.tell(EntityEnvelope(1, "hello-1"), probe.ref)
            probe.expectMsg("hello-1")
          }

          system.stop(sharding)
        } finally {
          failShardGetEntities = Map.empty
        }
      }

      s"recover when shard storing a start event fails $wayToFail" in {
        val storeProbe = TestProbe()
        system.eventStream.subscribe(storeProbe.ref, classOf[ShardStoreCreated])

        val sharding = ClusterSharding(system).start(
          s"shardStoreStart-$wayToFail",
          Props[EntityActor],
          ClusterShardingSettings(system).withRememberEntities(true),
          extractEntityId,
          extractShardId)

        // trigger shard start and store creation
        val probe = TestProbe()
        sharding.tell(EntityEnvelope(1, "hello-1"), probe.ref)
        var shardStore = storeProbe.expectMsgType[ShardStoreCreated].store
        probe.expectMsg("hello-1")

        // hit shard with other entity that will fail
        shardStore.tell(FakeShardStoreActor.FailAddEntity("11", wayToFail), storeProbe.ref)
        storeProbe.expectMsg(Done)

        sharding.tell(EntityEnvelope(11, "hello-11"), probe.ref)

        // do we get an answer here? shard crashes
        probe.expectNoMessage()
        if (wayToFail == StopStore || wayToFail == CrashStore) {
          // a new store should be started
          shardStore = storeProbe.expectMsgType[ShardStoreCreated].store
        }

        val stopFailingProbe = TestProbe()
        shardStore.tell(FakeShardStoreActor.ClearFail("11"), stopFailingProbe.ref)
        stopFailingProbe.expectMsg(Done)

        // it takes a while - timeout hits and then backoff
        awaitAssert({
          sharding.tell(EntityEnvelope(11, "hello-11-2"), probe.ref)
          probe.expectMsg("hello-11-2")
        }, 10.seconds)
        system.stop(sharding)
      }

      s"recover on abrupt entity stop when storing a stop event fails $wayToFail" in {
        val storeProbe = TestProbe()
        system.eventStream.subscribe(storeProbe.ref, classOf[ShardStoreCreated])

        val sharding = ClusterSharding(system).start(
          s"shardStoreStopAbrupt-$wayToFail",
          Props[EntityActor],
          ClusterShardingSettings(system).withRememberEntities(true),
          extractEntityId,
          extractShardId)

        val probe = TestProbe()

        // trigger shard start and store creation
        sharding.tell(EntityEnvelope(1, "hello-1"), probe.ref)
        val shard1Store = storeProbe.expectMsgType[ShardStoreCreated].store
        probe.expectMsg("hello-1")

        // fail it when stopping
        shard1Store.tell(FakeShardStoreActor.FailRemoveEntity("1", wayToFail), storeProbe.ref)
        storeProbe.expectMsg(Done)

        // FIXME restart without passivating is not saved and re-started again without storing the stop so this isn't testing anything
        sharding ! EntityEnvelope(1, "stop")

        shard1Store.tell(FakeShardStoreActor.ClearFail("1"), storeProbe.ref)
        storeProbe.expectMsg(Done)

        // it takes a while - timeout hits and then backoff
        awaitAssert({
          sharding.tell(EntityEnvelope(1, "hello-2"), probe.ref)
          probe.expectMsg("hello-2")
        }, 10.seconds)
        system.stop(sharding)
      }

      s"recover on graceful entity stop when storing a stop event fails $wayToFail" in {
        val storeProbe = TestProbe()
        system.eventStream.subscribe(storeProbe.ref, classOf[ShardStoreCreated])

        val sharding = ClusterSharding(system).start(
          s"shardStoreStopGraceful-$wayToFail",
          Props[EntityActor],
          ClusterShardingSettings(system).withRememberEntities(true),
          extractEntityId,
          extractShardId,
          new ShardCoordinator.LeastShardAllocationStrategy(rebalanceThreshold = 1, maxSimultaneousRebalance = 3),
          "graceful-stop")

        val probe = TestProbe()

        // trigger shard start and store creation
        sharding.tell(EntityEnvelope(1, "hello-1"), probe.ref)
        val shard1Store = storeProbe.expectMsgType[ShardStoreCreated].store
        probe.expectMsg("hello-1")

        // fail it when stopping
        shard1Store.tell(FakeShardStoreActor.FailRemoveEntity("1", wayToFail), storeProbe.ref)
        storeProbe.expectMsg(Done)

        sharding ! EntityEnvelope(1, "graceful-stop")

        shard1Store.tell(FakeShardStoreActor.ClearFail("1"), storeProbe.ref)
        storeProbe.expectMsg(Done)

        // it takes a while?
        awaitAssert({
          sharding.tell(EntityEnvelope(1, "hello-2"), probe.ref)
          probe.expectMsg("hello-2")
        }, 5.seconds)
        system.stop(sharding)
      }

      s"recover when coordinator storing shard start fails $wayToFail" in {
        val storeProbe = TestProbe()
        system.eventStream.subscribe(storeProbe.ref, classOf[CoordinatorStoreCreated])

        val sharding = ClusterSharding(system).start(
          s"coordinatorStoreStopGraceful-$wayToFail",
          Props[EntityActor],
          ClusterShardingSettings(system).withRememberEntities(true),
          extractEntityId,
          extractShardId,
          new ShardCoordinator.LeastShardAllocationStrategy(rebalanceThreshold = 1, maxSimultaneousRebalance = 3),
          "graceful-stop")

        val probe = TestProbe()

        // coordinator store is triggered by coordinator starting up
        var coordinatorStore = storeProbe.expectMsgType[CoordinatorStoreCreated].store
        coordinatorStore.tell(FakeCoordinatorStoreActor.FailAddShard("1", wayToFail), probe.ref)
        probe.expectMsg(Done)

        sharding.tell(EntityEnvelope(1, "hello-1"), probe.ref)
        probe.expectNoMessage(1.second) // because shard cannot start while store failing

        if (wayToFail == StopStore || wayToFail == CrashStore) {
          // a new store should be started
          coordinatorStore = storeProbe.expectMsgType[CoordinatorStoreCreated].store
        }

        // fail it when stopping
        coordinatorStore.tell(FakeCoordinatorStoreActor.ClearFailShard("1"), storeProbe.ref)
        storeProbe.expectMsg(Done)

        probe.awaitAssert({
          sharding.tell(EntityEnvelope(1, "hello-2"), probe.ref)
          probe.expectMsg("hello-2") // should now work again
        }, 5.seconds)

        system.stop(sharding)
      }
    }
  }

}