package fr.acinq.eclair.router

import akka.actor.Status.Failure
import akka.testkit.TestProbe
import fr.acinq.bitcoin.Script.{pay2wsh, write}
import fr.acinq.bitcoin.{Block, Satoshi, Transaction, TxOut}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.BITCOIN_FUNDING_EXTERNAL_CHANNEL_SPENT
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.router.Announcements.makeChannelUpdate
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate, Error, NodeAnnouncement}
import fr.acinq.eclair.{randomKey, toShortId}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

/**
  * Created by PM on 29/08/2016.
  */
@RunWith(classOf[JUnitRunner])
class RouterSpec extends BaseRouterSpec {

  test("properly announce valid new channels and ignore invalid ones") { case (router, watcher) =>
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[NetworkEvent])

    val channelId_ac = toShortId(420000, 5, 0)
    val chan_ac = channelAnnouncement(channelId_ac, priv_a, priv_c, priv_funding_a, priv_funding_c)
    val update_ac = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, c, channelId_ac, cltvExpiryDelta = 7, 0, feeBaseMsat = 766000, feeProportionalMillionths = 10)
    // a-x will not be found
    val priv_x = randomKey
    val chan_ax = channelAnnouncement(42001, priv_a, priv_x, priv_funding_a, randomKey)
    val update_ax = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, priv_x.publicKey, chan_ax.shortChannelId, cltvExpiryDelta = 7, 0, feeBaseMsat = 766000, feeProportionalMillionths = 10)
    // a-y will have an invalid script
    val priv_y = randomKey
    val priv_funding_y = randomKey
    val chan_ay = channelAnnouncement(42002, priv_a, priv_y, priv_funding_a, priv_funding_y)
    val update_ay = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, priv_y.publicKey, chan_ay.shortChannelId, cltvExpiryDelta = 7, 0, feeBaseMsat = 766000, feeProportionalMillionths = 10)
    // a-z will be spent
    val priv_z = randomKey
    val priv_funding_z = randomKey
    val chan_az = channelAnnouncement(42003, priv_a, priv_z, priv_funding_a, priv_funding_z)
    val update_az = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, priv_z.publicKey, chan_az.shortChannelId, cltvExpiryDelta = 7, 0, feeBaseMsat = 766000, feeProportionalMillionths = 10)

    router ! chan_ac
    router ! chan_ax
    router ! chan_ay
    router ! chan_az
    // router won't validate channels before it has a recent enough channel update
    router ! update_ac
    router ! update_ax
    router ! update_ay
    router ! update_az
    router ! TickValidate // we manually trigger a validation
    watcher.expectMsg(ParallelGetRequest(chan_ac :: chan_ax :: chan_ay :: chan_az :: Nil))
    watcher.send(router, ParallelGetResponse(
      IndividualResult(chan_ac, Some(Transaction(version = 0, txIn = Nil, txOut = TxOut(Satoshi(1000000), write(pay2wsh(Scripts.multiSig2of2(funding_a, funding_c)))) :: Nil, lockTime = 0)), true) ::
        IndividualResult(chan_ax, None, false) ::
        IndividualResult(chan_ay, Some(Transaction(version = 0, txIn = Nil, txOut = TxOut(Satoshi(1000000), write(pay2wsh(Scripts.multiSig2of2(funding_a, randomKey.publicKey)))) :: Nil, lockTime = 0)), true) ::
        IndividualResult(chan_az, Some(Transaction(version = 0, txIn = Nil, txOut = TxOut(Satoshi(1000000), write(pay2wsh(Scripts.multiSig2of2(funding_a, priv_funding_z.publicKey)))) :: Nil, lockTime = 0)), false) :: Nil))
    watcher.expectMsgType[WatchSpentBasic]
    watcher.expectNoMsg(1 second)

    eventListener.expectMsg(ChannelDiscovered(chan_ac, Satoshi(1000000)))
  }

  test("properly announce lost channels and nodes") { case (router, watcher) =>
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[NetworkEvent])

    router ! WatchEventSpentBasic(BITCOIN_FUNDING_EXTERNAL_CHANNEL_SPENT(channelId_ab))
    eventListener.expectMsg(ChannelLost(channelId_ab))
    // a doesn't have any channels, b still has one with c
    eventListener.expectMsg(NodeLost(a))
    eventListener.expectNoMsg(200 milliseconds)

    router ! WatchEventSpentBasic(BITCOIN_FUNDING_EXTERNAL_CHANNEL_SPENT(channelId_cd))
    eventListener.expectMsg(ChannelLost(channelId_cd))
    // d doesn't have any channels, c still has one with b
    eventListener.expectMsg(NodeLost(d))
    eventListener.expectNoMsg(200 milliseconds)

    router ! WatchEventSpentBasic(BITCOIN_FUNDING_EXTERNAL_CHANNEL_SPENT(channelId_bc))
    eventListener.expectMsg(ChannelLost(channelId_bc))
    // now b and c do not have any channels
    eventListener.expectMsgAllOf(NodeLost(b), NodeLost(c))
    eventListener.expectNoMsg(200 milliseconds)

  }

  test("handle bad signature for ChannelAnnouncement") { case (router, _) =>
    val sender = TestProbe()
    val channelId_ac = toShortId(420000, 5, 0)
    val chan_ac = channelAnnouncement(channelId_ac, priv_a, priv_c, priv_funding_a, priv_funding_c)
    val buggy_chan_ac = chan_ac.copy(nodeSignature1 = chan_ac.nodeSignature2)
    sender.send(router, buggy_chan_ac)
    sender.expectMsgType[Error]
  }

  test("handle bad signature for NodeAnnouncement") { case (router, _) =>
    val sender = TestProbe()
    val buggy_ann_a = ann_a.copy(signature = ann_b.signature, timestamp = ann_a.timestamp + 1)
    sender.send(router, buggy_ann_a)
    sender.expectMsgType[Error]
  }

  test("handle bad signature for ChannelUpdate") { case (router, _) =>
    val sender = TestProbe()
    val buggy_channelUpdate_ab = channelUpdate_ab.copy(signature = ann_b.signature, timestamp = channelUpdate_ab.timestamp + 1)
    sender.send(router, buggy_channelUpdate_ab)
    sender.expectMsgType[Error]
  }

  test("route not found (unreachable target)") { case (router, _) =>
    val sender = TestProbe()
    // no route a->f
    sender.send(router, RouteRequest(a, f))
    sender.expectMsg(Failure(RouteNotFound))
  }

  test("route not found (non-existing source)") { case (router, _) =>
    val sender = TestProbe()
    // no route a->f
    sender.send(router, RouteRequest(randomKey.publicKey, f))
    sender.expectMsg(Failure(RouteNotFound))
  }

  test("route not found (non-existing target)") { case (router, _) =>
    val sender = TestProbe()
    // no route a->f
    sender.send(router, RouteRequest(a, randomKey.publicKey))
    sender.expectMsg(Failure(RouteNotFound))
  }

  test("route found") { case (router, _) =>
    val sender = TestProbe()
    sender.send(router, RouteRequest(a, d))
    val res = sender.expectMsgType[RouteResponse]
    assert(res.hops.map(_.nodeId).toList === a :: b :: c :: Nil)
    assert(res.hops.last.nextNodeId === d)
  }

  test("route found (with extra routing info)") { case (router, _) =>
    val sender = TestProbe()
    val x = randomKey.publicKey
    val y = randomKey.publicKey
    val z = randomKey.publicKey
    val extraHop_cx = ExtraHop(c, 1, 10, 11, 12)
    val extraHop_xy = ExtraHop(x, 1, 10, 11, 12)
    val extraHop_yz = ExtraHop(y, 2, 20, 21, 22)
    sender.send(router, RouteRequest(a, z, assistedRoutes = Seq(extraHop_cx :: extraHop_xy :: extraHop_yz :: Nil)))
    val res = sender.expectMsgType[RouteResponse]
    assert(res.hops.map(_.nodeId).toList === a :: b :: c :: x :: y :: Nil)
    assert(res.hops.last.nextNodeId === z)
  }

  test("route not found (channel disabled)") { case (router, _) =>
    val sender = TestProbe()
    sender.send(router, RouteRequest(a, d))
    val res = sender.expectMsgType[RouteResponse]
    assert(res.hops.map(_.nodeId).toList === a :: b :: c :: Nil)
    assert(res.hops.last.nextNodeId === d)

    val channelUpdate_cd1 = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_c, d, channelId_cd, cltvExpiryDelta = 3, 0, feeBaseMsat = 153000, feeProportionalMillionths = 4, enable = false)
    sender.send(router, channelUpdate_cd1)
    sender.send(router, RouteRequest(a, d))
    sender.expectMsg(Failure(RouteNotFound))
  }

  test("temporary channel exclusion") { case (router, _) =>
    val sender = TestProbe()
    sender.send(router, RouteRequest(a, d))
    sender.expectMsgType[RouteResponse]
    val bc = ChannelDesc(channelId_bc, b, c)
    // let's exclude channel b->c
    sender.send(router, ExcludeChannel(bc))
    sender.send(router, RouteRequest(a, d))
    sender.expectMsg(Failure(RouteNotFound))
    // note that cb is still available!
    sender.send(router, RouteRequest(d, a))
    sender.expectMsgType[RouteResponse]
    // let's remove the exclusion
    sender.send(router, LiftChannelExclusion(bc))
    sender.send(router, RouteRequest(a, d))
    sender.expectMsgType[RouteResponse]
  }

  test("export graph in dot format") { case (router, _) =>
    val sender = TestProbe()
    sender.send(router, 'dot)
    val dot = sender.expectMsgType[String]
    /*Files.write(dot.getBytes(), new File("graph.dot"))

    import scala.sys.process._
    val input = new ByteArrayInputStream(dot.getBytes)
    val output = new ByteArrayOutputStream()
    "dot -Tpng" #< input #> output !
    val img = output.toByteArray
    Files.write(img, new File("graph.png"))*/
  }

  test("send routing state") { case (router, _) =>
    val sender = TestProbe()
    val receiver = TestProbe()
    sender.send(router, SendRoutingState(receiver.ref))
    for (_ <- 0 until 4) receiver.expectMsgType[ChannelAnnouncement]
    for (_ <- 0 until 6) receiver.expectMsgType[NodeAnnouncement]
    for (_ <- 0 until 8) receiver.expectMsgType[ChannelUpdate]
  }

}
