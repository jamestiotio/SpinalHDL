package spinal.lib.bus.tilelink.coherent

import spinal.core._
import spinal.lib._
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.bus.tilelink._
import spinal.lib.pipeline._

import scala.collection.mutable.ArrayBuffer
import scala.util.Random

case class HubParameters(unp : NodeParameters,
                         slotCount : Int,
                         cacheSize: Int,
                         wayCount: Int,
                         lineSize : Int,
                         probeCount : Int = 8,
                         aBufferCount: Int = 4,
                         cBufferCount: Int = 2,
                         cSourceCount: Int = 4) {
  val addressWidth = unp.m.addressWidth
  val dataWidth = unp.m.dataWidth
  val dataBytes = dataWidth/8
  val waySize = cacheSize/wayCount
  val sets = cacheSize/64/wayCount
  val linePerWay = waySize/lineSize
  val tagRange = addressWidth-1 downto log2Up(linePerWay*lineSize)
  val lineRange = tagRange.low-1 downto log2Up(lineSize)
  val wordRange = log2Up(lineSize)-1 downto log2Up(dataBytes)
  val refillRange = tagRange.high downto lineRange.low
  val blockRange = addressWidth-1 downto log2Up(lineSize)
  val blockSize = lineSize
  val setsRange = lineRange
}


/*
A transaction will always :
- Wait no slot conflicts
- [Fetch tags]
- [Probe stuff]
- [wait probe completion]
- aquire:
  - [writeback/fill cache cmd]
  - [Wait fill completion]
  - [Read data] + [update tags] + grantData/grant
  - wait up.E grant ack [and writeback] completion
- get / put
  - [writeback/fill cache cmd]
  - [Wait fill completion]
  - Read / write data

C : release data
- [Fetch tags]
- on hit [writeback cache] else write write to main mem
- wait completion


 */

class Hub(p : HubParameters) extends Component{
  import p._

  //  val slotDownOffset = 0
  //  val cDownIdOffset = slotDownOffset + (1 << log2Up(slotCount max p.cSourceCount))
  //  val downIdCount = cDownIdOffset + p.cSourceCount
  //
  //  val bps = p.nodes.map(_.toBusParameter())
  //  val upParam = NodeParameters.mergeMasters(p.nodes)
  //  val upParamPerNodeSourceWidth = nodes.map(_.m.sourceWidth).max
  //  val upBusParam = upParam.toBusParameter()
  //
  //  val downPutParam = NodeParameters(
  //    m = CoherentHub.downPutM2s(
  //      name           = this,
  //      addressWidth   = addressWidth,
  //      dataWidth      = dataWidth,
  //      blockSize      = blockSize,
  //      slotCount      = slotCount,
  //      cSourceCount   = cSourceCount
  //    ),
  //    s = S2mParameters(slaves = Nil)
  //  )
  //  val downGetParam = NodeParameters(
  //    m = CoherentHub.downGetM2s(
  //      name           = this,
  //      addressWidth   = addressWidth,
  //      dataWidth      = dataWidth,
  //      blockSize      = blockSize,
  //      slotCount      = slotCount
  //    ),
  //    s = S2mParameters(slaves = Nil)
  //  )
  //  val downPutBusParam = downPutParam.toBusParameter()
  //  val downGetBusParam = downGetParam.toBusParameter()
  //  val wordsPerBlock = blockSize / upBusParam.dataBytes
  //  def upSourceToNodeOh(source : UInt) = UIntToOh(source.takeHigh(log2Up(p.nodes.size)).asUInt, nodes.size)

  val ubp = p.unp.toBusParameter()
  val io = new Bundle{
    val up = slave(Bus(ubp))
    //    val downPut = master(Bus(downPutBusParam))
    //    val downGet = master(Bus(downGetBusParam))

    //    val ordering = new Bundle{
    //      val toDownGet = master Flow(CoherentHubOrdering(p))
    //      val toDownPut = master Flow(CoherentHubOrdering(p))
    //      val bToT = master Flow(CoherentHubOrdering(p))
    //      def all = List(toDownGet, toDownPut, bToT)
    //    }

  }



  case class Payload() extends Bundle {
    val mask = ubp.mask()
    val data = ubp.data()
  }

  val initializer = new Area{
    val initCycles = p.sets
    val counter = Reg(UInt(log2Up(initCycles) + 1 bits)) init(0)
    val done = counter.msb
    when(!done) {
      counter := counter + 1
    }
  }

  val frontendA = new Pipeline{
    val PAYLOAD = Stageable(Payload())
    val s0 = new Stage{
      driveFrom(io.up.a)
      val CMD = insert(io.up.a.payload.asNoData())
      val LAST = io.up.a.isLast()
      if(ubp.withDataA) {
        PAYLOAD.data := io.up.a.data
        PAYLOAD.mask := io.up.a.mask
      }

      val dataSplit = ubp.withDataA generate new Area{
        val buffer =  new Area{
          val ram = Mem.fill(aBufferCount*p.blockSize/p.unp.m.dataBytes)(Payload())
          val allocated = Reg(Bits(aBufferCount bits)) init(0)
          val set, clear = B(0, aBufferCount bits)
          val firstFree = OHToUInt(OHMasking.firstV2(~allocated))
          val full = allocated.andR
          val source = Vec.fill(aBufferCount)(Reg(ubp.source))
          val write = ram.writePort()
          allocated := (allocated | set) & ~clear
        }

        val withBeats = CMD.withBeats
        val hazard = withBeats && buffer.full
        haltIt(hazard)
        throwIt(!hazard && !LAST)

        val locked = RegInit(False) setWhen(buffer.write.valid)
        val lockedValue = RegNextWhen(buffer.firstFree, !locked)
        val bufferAllocated = locked ? lockedValue | buffer.firstFree
        buffer.write.valid := valid && withBeats && !hazard
        buffer.write.address := bufferAllocated @@ CMD.address(wordRange)
        buffer.write.data := PAYLOAD
        when(isFireing && LAST && withBeats){
          buffer.set(bufferAllocated) := True
          buffer.source.onSel(bufferAllocated)(_ := CMD.source)
          locked := False
        }
      }
    }

    val setsBusy = new Area{
      val hit, target = new Area {
        val mem = Mem.fill(sets)(Bool())
        val write = mem.writePort()
        write.valid := !initializer.done
        write.address := initializer.counter.resized
        write.data := False
      }
    }

    val s1 = new Stage(Connection.DIRECT()){
      val readAddress = s0.CMD.address(setsRange)
      val JUST_BUSY = insert(setsBusy.target.write.valid && setsBusy.target.write.address === readAddress)
      val JUST_FREE = insert(setsBusy.hit.write.valid && setsBusy.hit.write.address === readAddress)
    }
    val s2 = new Stage(Connection.M2S()){
      val hit    = setsBusy.hit.mem.readSync( s1.readAddress, isReady)
      val target = setsBusy.target.mem.readSync( s1.readAddress, isReady)
      val freed = RegNext(setsBusy.hit.write.valid && setsBusy.hit.write.address === s0.CMD.address(setsRange)) clearWhen(isReady)
      val hazard = (hit =/= target || s1.JUST_BUSY) && !s1.JUST_FREE && !freed
      haltIt(hazard)

      when(initializer.done) {
        setsBusy.target.write.valid := isFireing
        setsBusy.target.write.address := s0.CMD.address(setsRange)
        setsBusy.target.write.data := (s1.JUST_BUSY ? hit | !target)
      }
    }


  }

  val coherentMasters = unp.m.masters.filter(_.emits.withBCE)
  val coherentMasterCount = coherentMasters.size
  val coherentMasterToSource = coherentMasters.map(_.bSourceId)
  def MasterId() = UInt(log2Up(coherentMasterCount) bits)
  case class ProbeCmd() extends Bundle {
    val address = ubp.address()
    val toTrunk = Bool()
    val fromBranch = Bool()
    val selfMask = Bits(coherentMasterCount bits)
    val mask = Bits(coherentMasterCount bits)
  }
  val probe = new Area{
    val slots = for(i <- 0 until probeCount) yield new Area{
      val fire = False
      val valid = RegInit(False) clearWhen(fire)
      val set = Reg(UInt(setsRange.size bits))
      val pendings = Reg(UInt(log2Up(coherentMasterCount+1) bits))
      val lostIt = Reg(Bool())
      val decrement = Reg(Bool()) //This reg has double usage : Either set after a ProbeData writeback, either set for a probe-less wakeup
    }

    val push = Stream(ProbeCmd())
    val pushCmd = frontendA.s2(frontendA.s0.CMD)
    push.valid := frontendA.s2.isValid
    frontendA.s2.haltIt(!push.ready)
    push.address    := pushCmd.address
    push.toTrunk    := pushCmd.param =/= Param.Grow.NtoB
    push.fromBranch := pushCmd.param === Param.Grow.BtoT
    push.selfMask   := B(coherentMasters.map(_.sourceHit(pushCmd.source)))
    push.mask := ~push.selfMask

    val cmd = new Area{
      val sloted = RegInit(False)
      val full = slots.map(_.valid).andR
      val freeMask = OHMasking.firstV2(B(slots.map(!_.valid)))
      val requestsUnmasked = (push.mask & ~push.selfMask) | push.selfMask.andMask(push.fromBranch)
      val pendings = CountOne(requestsUnmasked)

      when(push.valid && !sloted && !full){
        slots.onMask(freeMask){s =>
          s.valid := True
          s.set := push.address(setsRange)
          s.pendings := pendings
          s.lostIt := False
          s.decrement := requestsUnmasked === 0
        }
        sloted := True
      }

      val bus = io.up.b
      val fired = Reg(Bits(coherentMasterCount bits)) init(0)
      val requests = requestsUnmasked & ~fired
      val masterOh = OHMasking.firstV2(requests)
      val selfProbe = (masterOh & push.selfMask).orR
      bus.valid   := push.valid && requests.orR
      bus.opcode  := Opcode.B.PROBE_BLOCK
      bus.param   := (push.toTrunk && !selfProbe) ? B(Param.Cap.toN, 3 bits) | B(Param.Cap.toB, 3 bits)
      bus.source  := OhMux(masterOh, coherentMasterToSource.map(id => U(id, ubp.sourceWidth bits)).toList)
      bus.address := push.address(blockRange) @@ U(0, blockRange.low bits)
      bus.size    := log2Up(p.blockSize)
      push.ready := requests === 0

      when(bus.fire){
        fired.asBools.onMask(masterOh)(_ := True)
      }

      when(push.ready){
        fired := 0
        sloted := False
      }
    }

    val rsp = new Area{
      val ackData = new Area{
        val bypass = Event
        val hits = slots.map(_.decrement).asBits
        val hit = hits.orR
        val oh = OHMasking.roundRobinNext(hits, bypass.fire)
        bypass.valid := hit && (!io.up.c.valid || io.up.c.isFirst())
        when(bypass.fire){
          slots.onMask(oh){ s =>
            s.decrement := False
          }
        }
      }

      val oh = slots.map(s => s.valid && s.set === io.up.c.address(setsRange)).asBits
      when(ackData.bypass.valid){
        oh := ackData.oh
      }

      val hitId = OHToUInt(oh)
      val isProbe = io.up.c.opcode === Opcode.C.PROBE_ACK
      val pendings = slots.map(_.pendings).read(hitId)
      val pendingsNext = pendings - 1
      val slotDecr = (io.up.c.valid && isProbe) || ackData.bypass.valid
      val slotCompletion = (pendings >> 1) === 0

      val outputA = Event
      outputA.valid := slotDecr && slotCompletion
      ackData.bypass.ready := !outputA.isStall
      val outputC = io.up.c.haltWhen(isProbe && (ackData.bypass.valid || !outputA.isStall))

      when(slotDecr && (ackData.bypass.valid || isProbe)){
        slots.onMask(oh){ s =>
          s.pendings := pendingsNext
          s.fire setWhen(!slotCompletion)
        }
      }

    }
  }


  frontendA.build()


//  probe.slots.foreach(e => out(e.pendings))
  master(probe.rsp.outputC.halfPipe())
  master(probe.rsp.outputA.halfPipe())
  out(probe.rsp.slotCompletion)
  out(frontendA.s2.isFireing)
  out(frontendA.setsBusy.hit.write)
  io.up.d.setIdle()
  io.up.e.ready := False

}



object HubGen extends App{
  def basicConfig(slotsCount : Int = 2, masterPerChannel : Int = 4, dataWidth : Int = 64, addressWidth : Int = 13, cacheSize : Int = 1024) = {
    HubParameters(
      unp = NodeParameters(
        m = M2sParameters(
          addressWidth = addressWidth,
          dataWidth = dataWidth,
          masters = List.tabulate(masterPerChannel)(mId =>
            M2sAgent(
              name = null,
              mapping = List.fill(1)(M2sSource(
                emits = M2sTransfers(
                  get = SizeRange(64),
                  putFull = SizeRange(64),
                  putPartial = SizeRange(64),
                  acquireT = SizeRange(64),
                  acquireB = SizeRange(64),
                  probeAckData = SizeRange(64)
                ),
                id = SizeMapping(mId*4, 4)
              ))
            ))
        ),
        s = S2mParameters(List(
          S2mAgent(
            name = null,
            emits = S2mTransfers(
              probe = SizeRange(64)
            ),
            sinkId = SizeMapping(0, slotsCount)
          )
        ))
      ),
      slotCount = slotsCount,
      cacheSize = cacheSize,
      wayCount  = 2,
      lineSize  = 64
    )
  }
  def gen = new Hub(HubGen.basicConfig(8, masterPerChannel = 4, dataWidth = 16, addressWidth = 32, cacheSize = 32*1024))
  SpinalVerilog(gen)

}

object HubSynt extends App{
  import spinal.lib.eda.bench._
  val rtls = ArrayBuffer[Rtl]()
  for(slots <- List(16)) { //Rtl.ffIo
    rtls += Rtl(SpinalVerilog((new Hub(HubGen.basicConfig(slots, masterPerChannel = 4, dataWidth = 16, addressWidth = 32, cacheSize = 32*1024)).setDefinitionName(s"Hub$slots"))))
  }
  val targets = XilinxStdTargets().take(2) ++ AlteraStdTargets(quartusCycloneIIPath = null)

  Bench(rtls, targets)
}

/*
Hub16 ->
Artix 7 -> 163 Mhz 164 LUT 215 FF
Artix 7 -> 268 Mhz 180 LUT 215 FF
Cyclone V -> FAILED
Cyclone IV -> 181 Mhz 217 LUT 214 FF

Hub16 ->
Artix 7 -> 165 Mhz 160 LUT 215 FF
Artix 7 -> 269 Mhz 174 LUT 215 FF
Cyclone V -> FAILED
Cyclone IV -> 181 Mhz 217 LUT 214 FF

 */