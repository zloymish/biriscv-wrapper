//******************************************************************************
// Copyright (c) 2019 - 2019, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// Ariane Tile Wrapper
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------

package biriscv

import chisel3._
import chisel3.util._
import chisel3.experimental.{IntParam, StringParam}

import scala.collection.mutable.{ListBuffer}

import freechips.rocketchip.config._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.diplomaticobjectmodel.logicaltree.{LogicalModuleTree, LogicalTreeNode, RocketLogicalTreeNode, ICacheLogicalTreeNode}
import freechips.rocketchip.rocket._
import freechips.rocketchip.subsystem.{RocketCrossingParams}
import freechips.rocketchip.tilelink._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.util._
import freechips.rocketchip.tile._
import freechips.rocketchip.amba.axi4._

case object BiRISCVTilesKey extends Field[Seq[BiRISCVTileParams]](Nil)

case class BiRISCVCoreParams(
  bootFreqHz: BigInt = BigInt(1700000000),
  rasEntries: Int = 4,
  btbEntries: Int = 16,
  bhtEntries: Int = 16,
  enableToFromHostCaching: Boolean = false,
) extends CoreParams {
  val useVM: Boolean = true
  val useUser: Boolean = true
  val useDebug: Boolean = true
  val useAtomics: Boolean = true
  val useAtomicsOnlyForIO: Boolean = false // copied from Rocket
  val useCompressed: Boolean = true
  override val useVector: Boolean = false
  val useSCIE: Boolean = false
  val useRVE: Boolean = false
  val mulDiv: Option[MulDivParams] = Some(MulDivParams()) // copied from Rocket
  val fpu: Option[FPUParams] = Some(FPUParams()) // copied fma latencies from Rocket
  val nLocalInterrupts: Int = 0
  val nPMPs: Int = 0 // TODO: Check
  val pmpGranularity: Int = 4 // copied from Rocket
  val nBreakpoints: Int = 0 // TODO: Check
  val useBPWatch: Boolean = false
  val nPerfCounters: Int = 29
  val haveBasicCounters: Boolean = true
  val haveFSDirty: Boolean = false
  val misaWritable: Boolean = false
  val haveCFlush: Boolean = false
  val nL2TLBEntries: Int = 512 // copied from Rocket
  val mtvecInit: Option[BigInt] = Some(BigInt(0)) // copied from Rocket
  val mtvecWritable: Boolean = true // copied from Rocket
  val instBits: Int = if (useCompressed) 16 else 32
  val lrscCycles: Int = 80 // copied from Rocket
  val decodeWidth: Int = 2 // TODO: Check
  val fetchWidth: Int = 2 // TODO: Check
  val retireWidth: Int = 2
}

// TODO: BTBParams, DCacheParams, ICacheParams are incorrect in DTB... figure out defaults in Ariane and put in DTB
case class BiRISCVTileParams(
  name: Option[String] = Some("biriscv_tile"),
  hartId: Int = 0,
  beuAddr: Option[BigInt] = None,
  blockerCtrlAddr: Option[BigInt] = None,
  btb: Option[BTBParams] = Some(BTBParams()),
  core: BiRISCVCoreParams = BiRISCVCoreParams(),
  dcache: Option[DCacheParams] = Some(DCacheParams()),
  icache: Option[ICacheParams] = Some(ICacheParams()),
  boundaryBuffers: Boolean = false,
  trace: Boolean = false
  ) extends TileParams

class BiRISCVTile(
  val biriscvParams: BiRISCVTileParams,
  crossing: ClockCrossingType,
  lookup: LookupByHartIdImpl,
  q: Parameters,
  logicalTreeNode: LogicalTreeNode)
  extends BaseTile(biriscvParams, crossing, lookup, q)
  with SinksExternalInterrupts
  with SourcesExternalNotifications
{
  /**
   * Setup parameters:
   * Private constructor ensures altered LazyModule.p is used implicitly
   */
  def this(params: BiRISCVTileParams, crossing: RocketCrossingParams, lookup: LookupByHartIdImpl, logicalTreeNode: LogicalTreeNode)(implicit p: Parameters) =
    this(params, crossing.crossingType, lookup, p, logicalTreeNode)

  val intOutwardNode = IntIdentityNode()
  val slaveNode = TLIdentityNode()
  val masterNode = visibilityNode

  tlOtherMastersNode := tlMasterXbar.node
  masterNode :=* tlOtherMastersNode
  DisableMonitors { implicit p => tlSlaveXbar.node :*= slaveNode }

  val cpuDevice: SimpleDevice = new SimpleDevice("cpu", Seq("ultraembedded, biriscv", "riscv")) {
    override def parent = Some(ResourceAnchors.cpus)
    override def describe(resources: ResourceBindings): Description = {
      val Description(name, mapping) = super.describe(resources)
      Description(name, mapping ++
                        cpuProperties ++
                        nextLevelCacheProperty ++
                        tileProperties)
    }
  }

  ResourceBinding {
    Resource(cpuDevice, "reg").bind(ResourceAddress(hartId))
  }

  override def makeMasterBoundaryBuffers(implicit p: Parameters) = {
    if (!biriscvParams.boundaryBuffers) super.makeMasterBoundaryBuffers
    else TLBuffer(BufferParams.none, BufferParams.flow, BufferParams.none, BufferParams.flow, BufferParams(1))
  }

  override def makeSlaveBoundaryBuffers(implicit p: Parameters) = {
    if (!biriscvParams.boundaryBuffers) super.makeSlaveBoundaryBuffers
    else TLBuffer(BufferParams.flow, BufferParams.none, BufferParams.none, BufferParams.none, BufferParams.none)
  }

  val fakeRocketParams = RocketTileParams(
    dcache = biriscvParams.dcache,
    hartId = biriscvParams.hartId,
    name   = biriscvParams.name,
    btb    = biriscvParams.btb,
    core = RocketCoreParams(
      bootFreqHz          = biriscvParams.core.bootFreqHz,
      useVM               = biriscvParams.core.useVM,
      useUser             = biriscvParams.core.useUser,
      useDebug            = biriscvParams.core.useDebug,
      useAtomics          = biriscvParams.core.useAtomics,
      useAtomicsOnlyForIO = biriscvParams.core.useAtomicsOnlyForIO,
      useCompressed       = biriscvParams.core.useCompressed,
      useSCIE             = biriscvParams.core.useSCIE,
      mulDiv              = biriscvParams.core.mulDiv,
      fpu                 = biriscvParams.core.fpu,
      nLocalInterrupts    = biriscvParams.core.nLocalInterrupts,
      nPMPs               = biriscvParams.core.nPMPs,
      nBreakpoints        = biriscvParams.core.nBreakpoints,
      nPerfCounters       = biriscvParams.core.nPerfCounters,
      haveBasicCounters   = biriscvParams.core.haveBasicCounters,
      misaWritable        = biriscvParams.core.misaWritable,
      haveCFlush          = biriscvParams.core.haveCFlush,
      nL2TLBEntries       = biriscvParams.core.nL2TLBEntries,
      mtvecInit           = biriscvParams.core.mtvecInit,
      mtvecWritable       = biriscvParams.core.mtvecWritable
    )
  )
  val rocketLogicalTree: RocketLogicalTreeNode = new RocketLogicalTreeNode(cpuDevice, fakeRocketParams, None, p(XLen))

  override lazy val module = new BiRISCVTileModuleImp(this)

  /**
   * Setup AXI4 memory interface.
   * THESE ARE CONSTANTS.
   */
  val portName = "biriscv-mem-port-axi4"
  val idBits = 4
  val beatBytes = masterPortBeatBytes
  val sourceBits = 1 // equiv. to userBits (i think)

  val memAXI4NodeD = AXI4MasterNode(
    Seq(AXI4MasterPortParameters(
      masters = Seq(AXI4MasterParameters(
        name = portName + "-d",
        id = IdRange(0, 1 << idBits))))))

  val memoryTapD = TLIdentityNode()
  (tlMasterXbar.node
    := memoryTapD
    := TLBuffer()
    := TLFIFOFixer(TLFIFOFixer.all) // fix FIFO ordering
    := TLWidthWidget(beatBytes) // reduce size of TL
    := AXI4ToTL() // convert to TL
    := AXI4UserYanker(Some(2)) // remove user field on AXI interface. need but in reality user intf. not needed
    := AXI4Fragmenter() // deal with multi-beat xacts
    := memAXI4NodeD)

  val memAXI4NodeI = AXI4MasterNode(
    Seq(AXI4MasterPortParameters(
      masters = Seq(AXI4MasterParameters(
        name = portName + "-i",
        id = IdRange(0, 1 << idBits))))))

  val memoryTapI = TLIdentityNode()
  (tlMasterXbar.node
    := memoryTapI
    := TLBuffer()
    := TLFIFOFixer(TLFIFOFixer.all) // fix FIFO ordering
    := TLWidthWidget(beatBytes) // reduce size of TL
    := AXI4ToTL() // convert to TL
    := AXI4UserYanker(Some(2)) // remove user field on AXI interface. need but in reality user intf. not needed
    := AXI4Fragmenter() // deal with multi-beat xacts
    := memAXI4NodeI)

  def connectBiRISCVInterrupts(debug: Bool, msip: Bool, mtip: Bool, m_s_eip: UInt) {
    val (interrupts, _) = intSinkNode.in(0)
    debug := interrupts(0)
    msip := interrupts(1)
    mtip := interrupts(2)
    m_s_eip := Cat(interrupts(4), interrupts(3))
  }
}

class BiRISCVTileModuleImp(outer: BiRISCVTile) extends BaseTileModuleImp(outer){
  // annotate the parameters
  Annotated.params(this, outer.biriscvParams)

  val debugBaseAddr = BigInt(0x0) // CONSTANT: based on default debug module
  val debugSz = BigInt(0x1000) // CONSTANT: based on default debug module
  val tohostAddr = BigInt(0x80001000L) // CONSTANT: based on default sw (assume within extMem region)
  val fromhostAddr = BigInt(0x80001040L) // CONSTANT: based on default sw (assume within extMem region)

  // have the main memory be cached, but don't cache tohost/fromhost addresses
  // TODO: current cache subsystem can only support 1 cacheable region... so cache AFTER the tohost/fromhost addresses
  val wordOffset = 0x40
  val (cacheableRegionBases, cacheableRegionSzs) = if (outer.biriscvParams.core.enableToFromHostCaching) {
    val bases = Seq(p(ExtMem).get.master.base, BigInt(0x0), BigInt(0x0), BigInt(0x0), BigInt(0x0))
    val sizes   = Seq(p(ExtMem).get.master.size, BigInt(0x0), BigInt(0x0), BigInt(0x0), BigInt(0x0))
    (bases, sizes)
  } else {
    val bases = Seq(                                                          fromhostAddr + 0x40,              p(ExtMem).get.master.base, BigInt(0x0), BigInt(0x0), BigInt(0x0))
    val sizes = Seq(p(ExtMem).get.master.size - (fromhostAddr + 0x40 - p(ExtMem).get.master.base), tohostAddr - p(ExtMem).get.master.base, BigInt(0x0), BigInt(0x0), BigInt(0x0))
    (bases, sizes)
  }
  val cacheableRegionCnt   = cacheableRegionBases.length

  val traceInstSz = (new freechips.rocketchip.rocket.TracedInstruction).getWidth

  // connect the ariane core
  val core = Module(new BiRISCVCoreBlackbox(
    // traceport params
    // TODO: We don't have tracing support now, implement later
    // traceportEnabled = outer.biriscvParams.trace,
    // traceportSz = (outer.biriscvParams.core.retireWidth * traceInstSz),

    // general core params
    numRASEntriesWidth = log2(outer.biriscvParams.core.rasEntries),
    numBTBEntriesWidth = log2(outer.biriscvParams.core.btbEntries),
    numBHTEntriesWidth = log2(outer.biriscvParams.core.bhtEntries),

    memCacheAddrMin = cacheableRegionBases,
    memCacheAddrMax = cacheableRegionBases + cacheableRegionSzs,

    coreID = outer.biriscvParams.hartId,
    icacheAXIID = outer.biriscvParams.hartId,
    dcacheAXIID = outer.biriscvParams.hartId,
  ))

  core.io.clk_i := clock
  core.io.rst_ni := ~reset.asBool

  outer.connectArianeInterrupts(core.io.debug_req_i, core.io.ipi_i, core.io.time_irq_i, core.io.irq_i)

  // if (outer.biriscvParams.trace) {
  //   // unpack the trace io from a UInt into Vec(TracedInstructions)
  //   //outer.traceSourceNode.bundle <> core.io.trace_o.asTypeOf(outer.traceSourceNode.bundle)
  //
  //   for (w <- 0 until outer.biriscvParams.core.retireWidth) {
  //     outer.traceSourceNode.bundle(w).clock     := core.io.trace_o(traceInstSz*w + 0).asClock
  //     outer.traceSourceNode.bundle(w).reset     := core.io.trace_o(traceInstSz*w + 1)
  //     outer.traceSourceNode.bundle(w).valid     := core.io.trace_o(traceInstSz*w + 2)
  //     outer.traceSourceNode.bundle(w).iaddr     := core.io.trace_o(traceInstSz*w + 42, traceInstSz*w + 3)
  //     outer.traceSourceNode.bundle(w).insn      := core.io.trace_o(traceInstSz*w + 74, traceInstSz*w + 43)
  //     outer.traceSourceNode.bundle(w).priv      := core.io.trace_o(traceInstSz*w + 77, traceInstSz*w + 75)
  //     outer.traceSourceNode.bundle(w).exception := core.io.trace_o(traceInstSz*w + 78)
  //     outer.traceSourceNode.bundle(w).interrupt := core.io.trace_o(traceInstSz*w + 79)
  //     outer.traceSourceNode.bundle(w).cause     := core.io.trace_o(traceInstSz*w + 87, traceInstSz*w + 80)
  //     outer.traceSourceNode.bundle(w).tval      := core.io.trace_o(traceInstSz*w + 127, traceInstSz*w + 88)
  //   }
  // } else {
  //   outer.traceSourceNode.bundle := DontCare
  //   outer.traceSourceNode.bundle map (t => t.valid := false.B)
  // }

  // connect the axi interface
  outer.memAXI4NodeD.out foreach { case (out, edgeOut) =>
    core.io.axi_d_awready_i   := out.aw.ready
    out.aw.valid              := core.io.axi_d_awvalid_o
    out.aw.bits.id            := core.io.axi_d_awid_o
    out.aw.bits.addr          := core.io.axi_d_awaddr_o
    out.aw.bits.len           := core.io.axi_d_awlen_o
    out.aw.bits.size          := //TODO: implement a shift distance detector in wrapper sv
    out.aw.bits.burst         := core.io.axi_d_awburst_o
    out.aw.bits.lock          := 0.U  // Unused by processor - default to Normal access
    out.aw.bits.cache         := //TODO: implement it in wrapper sv (only care about the first two)
    out.aw.bits.prot          := 0.U  // Unused by processor - default to Unpriviliege, Unsafe, Data access (or implement it in the wrapper sv)
    out.aw.bits.qos           := 0.U  // Unused by processor

    core.io.axi_d_wready_i    := out.w.ready
    out.w.valid               := core.io.axi_d_wvalid_o
    out.w.bits.data           := core.io.axi_d_wdata_o
    out.w.bits.strb           := core.io.axi_d_wstrb_o
    out.w.bits.last           := core.io.axi_d_wlast_o

    out.b.ready               := core.io.axi_d_bready_o
    core.io.axi_d_bvalid_i    := out.b.valid
    core.io.axi_d_bid_i       := out.b.bits.id
    core.io.axi_d_bresp_i     := out.b.bits.resp

    core.io.axi_d_arready_i   := out.ar.ready
    out.ar.valid              := core.io.axi_d_arvalid_o
    out.ar.bits.id            := core.io.axi_d_arid_o
    out.ar.bits.addr          := core.io.axi_d_araddr_o
    out.ar.bits.len           := core.io.axi_d_arlen_o
    out.ar.bits.size          := //TODO: implement a shift distance detector in wrapper sv
    out.ar.bits.burst         := core.io.axi_d_arburst_o
    out.ar.bits.lock          := 0.U  // Unused by processor - default to Normal access
    out.ar.bits.cache         := //TODO: implement it in wrapper sv (only care about the first two)
    out.ar.bits.prot          := 0.U  // Unused by processor - default to Unpriviliege, Unsafe, Data access (or implement it in the wrapper sv)
    out.ar.bits.qos           := 0.U  // Unused by processor

    out.r.ready               := core.io.axi_d_rready_o
    core.io.axi_d_rvalid_i    := out.r.valid
    core.io.axi_d_rid_i       := out.r.bits.id
    core.io.axi_d_rdata_i     := out.r.bits.data
    core.io.axi_d_rresp_i     := out.r.bits.resp
    core.io.axi_d_rlast_i     := out.r.bits.last
  }
  outer.memAXI4NodeI.out foreach { case (out, edgeOut) =>
    core.io.axi_i_awready_i   := out.aw.ready
    out.aw.valid              := core.io.axi_i_awvalid_o
    out.aw.bits.id            := core.io.axi_i_awid_o
    out.aw.bits.addr          := core.io.axi_i_awaddr_o
    out.aw.bits.len           := core.io.axi_i_awlen_o
    out.aw.bits.size          := 0.U //TODO: implement a shift distance detector in wrapper sv
    out.aw.bits.burst         := core.io.axi_i_awburst_o
    out.aw.bits.lock          := 0.U  // Unused by processor - default to Normal access
    out.aw.bits.cache         := 0.U //TODO: implement it in wrapper sv (only care about the first two)
    out.aw.bits.prot          := 0.U  // Unused by processor - default to Unpriviliege, Unsafe, Data access (or implement it in the wrapper sv)
    out.aw.bits.qos           := 0.U  // Unused by processor

    core.io.axi_i_wready_i    := out.w.ready
    out.w.valid               := core.io.axi_i_wvalid_o
    out.w.bits.data           := core.io.axi_i_wdata_o
    out.w.bits.strb           := core.io.axi_i_wstrb_o
    out.w.bits.last           := core.io.axi_i_wlast_o

    out.b.ready               := core.io.axi_i_bready_o
    core.io.axi_i_bvalid_i    := out.b.valid
    core.io.axi_i_bid_i       := out.b.bits.id
    core.io.axi_i_bresp_i     := out.b.bits.resp

    core.io.axi_i_arready_i   := out.ar.ready
    out.ar.valid              := core.io.axi_i_arvalid_o
    out.ar.bits.id            := core.io.axi_i_arid_o
    out.ar.bits.addr          := core.io.axi_i_araddr_o
    out.ar.bits.len           := core.io.axi_i_arlen_o
    out.ar.bits.size          := 0.U //TODO: implement a shift distance detector in wrapper sv
    out.ar.bits.burst         := core.io.axi_i_arburst_o
    out.ar.bits.lock          := 0.U  // Unused by processor - default to Normal access
    out.ar.bits.cache         := 0.U //TODO: implement it in wrapper sv (only care about the first two)
    out.ar.bits.prot          := 0.U  // Unused by processor - default to Unpriviliege, Unsafe, Data access (or implement it in the wrapper sv)
    out.ar.bits.qos           := 0.U  // Unused by processor

    out.r.ready               := core.io.axi_i_rready_o
    core.io.axi_i_rvalid_i    := out.r.valid
    core.io.axi_i_rid_i       := out.r.bits.id
    core.io.axi_i_rdata_i     := out.r.bits.data
    core.io.axi_i_rresp_i     := out.r.bits.resp
    core.io.axi_i_rlast_i     := out.r.bits.last
  }
}
