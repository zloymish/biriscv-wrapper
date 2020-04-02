//******************************************************************************
// Copyright (c) 2019 - 2019, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// biRISC-V Tile Wrapper
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------

package biriscv

import sys.process._

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

class BiRISCVCoreBlackbox(
  coreID : Int = 0,
  icacheAXIID : Int = 0,
  dcacheAXIID : Int = 0,
  supportBranchPrediction : Boolean = true,
  supportMulDiv : Boolean = true,
  supportSuper : Boolean = false,
  supportMMU : Boolean = false,
  supportDualIssue : Boolean = true,
  supportLoadBypass : Boolean = true,
  supportMulBypass : Boolean = true,
  supportRegfileXilinx : Boolean = false,
  extraDecodeStage : Boolean = false,
  memCacheAddrMin : Int = 0x80000000,
  memCacheAddrMax : Int = 0x8fffffff,
  numBTBEntriesWidth : Int = 5,
  numBHTEntriesWidth : Int = 9,
  gShareEnable : Boolean = false,
  numRASEntriesWidth : Int = 3)
  extends BlackBox(
    Map(
      "CORE_ID" -> IntParam(coreID),
      "ICACHE_AXI_ID" -> IntParam(icacheAXIID),
      "DCACHE_AXI_ID" -> IntParam(dcacheAXIID),
      "SUPPORT_BRANCH_PREDICTION" -> IntParam(supportBranchPrediction),
      "SUPPORT_MULDIV" -> IntParam(supportMulDiv),
      "SUPPORT_SUPER" -> IntParam(supportSuper),
      "SUPPORT_MMU" -> IntParam(supportMMU),
      "SUPPORT_DUAL_ISSUE" -> IntParam(supportDualIssue),
      "SUPPORT_LOAD_BYPASS" -> IntParam(supportLoadBypass),
      "SUPPORT_MUL_BYPASS" -> IntParam(supportMulBypass),
      "SUPPORT_REGFILE_XILINX" -> IntParam(supportRegfileXilinx),
      "EXTRA_DECODE_STAGE" -> IntParam(extraDecodeStage),
      "MEM_CACHE_ADDR_MIN" -> IntParam(memCacheAddrMin),
      "MEM_CACHE_ADDR_MAX" -> IntParam(memCacheAddrMax),
      "NUM_BTB_ENTRIES" -> IntParam(2 << numBTBEntriesWidth),
      "NUM_BTB_ENTRIES_W" -> IntParam(numBTBEntriesWidth),
      "NUM_BHT_ENTRIES" -> IntParam(2 << numBHTEntriesWidth),
      "NUM_BHT_ENTRIES_W" -> IntParam(numBHTEntriesWidth),
      "RAS_ENABLE" -> IntParam(numRASEntriesWidth != 0),
      "GSHARE_ENABLE" -> IntParam(gShareEnable),
      "BHT_ENABLE" -> IntParam(numBHTEntriesWidth != 0),
      "NUM_RAS_ENTRIES" -> IntParam(2 << numRASEntriesWidth),
      "NUM_RAS_ENTRIES_W" -> IntParam(numRASEntriesWidth)
    )
  )
  with HasBlackBoxResource
{
  val io = IO(new Bundle {
    // Inputs
    val clk_i = Input(Clock())
    val rst_i = Input(Bool()),
    val axi_i_awready_i = Input(Bool()),
    val axi_i_wready_i = Input(Bool()),
    val axi_i_bvalid_i = Input(Bool()),
    val axi_i_bresp_i = Input(UInt(2.W)),
    val axi_i_bid_i = Input(UInt(4.W)),
    val axi_i_arready_i = Input(Bool()),
    val axi_i_rvalid_i = Input(Bool()),
    val axi_i_rdata_i = Input(UInt(32.W)),
    val axi_i_rresp_i = Input(UInt(2.W))
    val axi_i_rid_i = Input(UInt(4.W))
    val axi_i_rlast_i = Input(Bool()),
    val axi_d_awready_i = Input(Bool()),
    val axi_d_wready_i = Input(Bool()),
    val axi_d_bvalid_i = Input(Bool()),
    val axi_d_bresp_i = Input(UInt(2.W)),
    val axi_d_bid_i = Input(UInt(4.W)),
    val axi_d_arready_i = Input(Bool()),
    val axi_d_rvalid_i = Input(Bool()),
    val axi_d_rdata_i = Input(UInt(32.W)),
    val axi_d_rresp_i = Input(UInt(2.W)),
    val axi_d_rid_i = Input(UInt(4.W)),
    val axi_d_rlast_i = Input(Bool()),
    val intr_i = Input(Bool()),
    val reset_vector_i = Input(UInt(32.W)),

    // Outputs
    val axi_i_awvalid_o = Output(Bool()),
    val axi_i_awaddr_o = Output(UInt(32.W)),
    val axi_i_awid_o = Output(UInt(4.W)),
    val axi_i_awlen_o = Output(UInt(8.W)),
    val axi_i_awburst_o = Output(UInt(2.W)),
    val axi_i_wvalid_o = Output(Bool()),
    val axi_i_wdata_o = Output(UInt(32.W)),
    val axi_i_wstrb_o = Output(UInt(4.W)),
    val axi_i_wlast_o = Output(Bool()),
    val axi_i_bready_o = Output(Bool()),
    val axi_i_arvalid_o = Output(Bool()),
    val axi_i_araddr_o = Output(UInt(32.W)),
    val axi_i_arid_o = Output(UInt(4.W)),
    val axi_i_arlen_o = Output(UInt(8.W)),
    val axi_i_arburst_o = Output(UInt(2.W)),
    val axi_i_rready_o = Output(Bool()),
    val axi_d_awvalid_o = Output(Bool()),
    val axi_d_awaddr_o = Output(UInt(32.W)),
    val axi_d_awid_o = Output(UInt(4.W)),
    val axi_d_awlen_o = Output(UInt(8.W)),
    val axi_d_awburst_o = Output(UInt(2.W)),
    val axi_d_wvalid_o = Output(Bool()),
    val axi_d_wdata_o = Output(UInt(32.W)),
    val axi_d_wstrb_o = Output(UInt(4.W)),
    val axi_d_wlast_o = Output(Bool()),
    val axi_d_bready_o = Output(Bool()),
    val axi_d_arvalid_o = Output(Bool()),
    val axi_d_araddr_o = Output(UInt(32.W)),
    val axi_d_arid_o = Output(UInt(4.W)),
    val axi_d_arlen_o = Output(UInt(8.W)),
    val axi_d_arburst_o = Output(UInt(2.W)),
    val axi_d_rready_o = Output(Bool())
  })

  // add wrapper/blackbox after it is pre-processed
  addResource("/vsrc/biriscv/src/top/riscv_top.v")
}
