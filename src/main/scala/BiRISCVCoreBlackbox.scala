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
  numBTBEntriesBits : Int = 5,
  numBHTEntriesBits : Int = 9,
  rasEnable : Boolean = true,
  gShareEnable : Boolean = false,
  bhtEnable : Boolean = true,
  numRASEntriesBits : Int = 3)
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
      "NUM_BTB_ENTRIES" -> IntParam(2 << numBTBEntriesBits),
      "NUM_BTB_ENTRIES_W" -> IntParam(numBTBEntriesBits),
      "NUM_BHT_ENTRIES" -> IntParam(2 << numBHTEntriesBits),
      "NUM_BHT_ENTRIES_W" -> IntParam(numBHTEntriesBits),
      "RAS_ENABLE" -> IntParam(rasEnable),
      "GSHARE_ENABLE" -> IntParam(gShareEnable),
      "BHT_ENABLE" -> IntParam(bhtEnable),
      "NUM_RAS_ENTRIES" -> IntParam(2 << numRASEntriesBits),
      "NUM_RAS_ENTRIES_W" -> IntParam(numRASEntriesBits)
    )
  )
  with HasBlackBoxResource
{
  val io = IO(new Bundle {
    val clk_i = Input(Clock())
    val rst_ni = Input(Bool())

    val axi_resp_i_aw_ready      = Input(Bool())
    val axi_req_o_aw_valid       = Output(Bool())
    val axi_req_o_aw_bits_id     = Output(UInt(axiIdWidth.W))
    val axi_req_o_aw_bits_addr   = Output(UInt(axiAddrWidth.W))
    val axi_req_o_aw_bits_len    = Output(UInt(8.W))
    val axi_req_o_aw_bits_size   = Output(UInt(3.W))
    val axi_req_o_aw_bits_burst  = Output(UInt(2.W))
    val axi_req_o_aw_bits_lock   = Output(Bool())
    val axi_req_o_aw_bits_cache  = Output(UInt(4.W))
    val axi_req_o_aw_bits_prot   = Output(UInt(3.W))
    val axi_req_o_aw_bits_qos    = Output(UInt(4.W))
    val axi_req_o_aw_bits_region = Output(UInt(4.W))
    val axi_req_o_aw_bits_atop   = Output(UInt(6.W))
    val axi_req_o_aw_bits_user   = Output(UInt(axiUserWidth.W))

    val axi_resp_i_w_ready    = Input(Bool())
    val axi_req_o_w_valid     = Output(Bool())
    val axi_req_o_w_bits_data = Output(UInt(axiDataWidth.W))
    val axi_req_o_w_bits_strb = Output(UInt((axiDataWidth/8).W))
    val axi_req_o_w_bits_last = Output(Bool())
    val axi_req_o_w_bits_user = Output(UInt(axiUserWidth.W))

    val axi_resp_i_ar_ready      = Input(Bool())
    val axi_req_o_ar_valid       = Output(Bool())
    val axi_req_o_ar_bits_id     = Output(UInt(axiIdWidth.W))
    val axi_req_o_ar_bits_addr   = Output(UInt(axiAddrWidth.W))
    val axi_req_o_ar_bits_len    = Output(UInt(8.W))
    val axi_req_o_ar_bits_size   = Output(UInt(3.W))
    val axi_req_o_ar_bits_burst  = Output(UInt(2.W))
    val axi_req_o_ar_bits_lock   = Output(Bool())
    val axi_req_o_ar_bits_cache  = Output(UInt(4.W))
    val axi_req_o_ar_bits_prot   = Output(UInt(3.W))
    val axi_req_o_ar_bits_qos    = Output(UInt(4.W))
    val axi_req_o_ar_bits_region = Output(UInt(4.W))
    val axi_req_o_ar_bits_user   = Output(UInt(axiUserWidth.W))

    val axi_req_o_b_ready      = Output(Bool())
    val axi_resp_i_b_valid     = Input(Bool())
    val axi_resp_i_b_bits_id   = Input(UInt(axiIdWidth.W))
    val axi_resp_i_b_bits_resp = Input(UInt(2.W))
    val axi_resp_i_b_bits_user = Input(UInt(axiUserWidth.W))

    val axi_req_o_r_ready      = Output(Bool())
    val axi_resp_i_r_valid     = Input(Bool())
    val axi_resp_i_r_bits_id   = Input(UInt(axiIdWidth.W))
    val axi_resp_i_r_bits_data = Input(UInt(axiDataWidth.W))
    val axi_resp_i_r_bits_resp = Input(UInt(2.W))
    val axi_resp_i_r_bits_last = Input(Bool())
    val axi_resp_i_r_bits_user = Input(UInt(axiUserWidth.W))
  })

  require((exeRegCnt <= execRegAvail) && (exeRegBase.length <= execRegAvail) && (exeRegSz.length <= execRegAvail), s"Currently only supports $execRegAvail execution regions")
  require((cacheRegCnt <= cacheRegAvail) && (cacheRegBase.length <= cacheRegAvail) && (cacheRegSz.length <= cacheRegAvail), s"Currently only supports $cacheRegAvail cacheable regions")

  // pre-process the verilog to remove "includes" and combine into one file
  val make = "make -C generators/ariane/src/main/resources/vsrc default "
  val proc = if (traceportEnabled) make + "EXTRA_PREPROC_OPTS=+define+FIRESIM_TRACE" else make
  require (proc.! == 0, "Failed to run preprocessing step")

  // add wrapper/blackbox after it is pre-processed
  addResource("/vsrc/ArianeCoreBlackbox.preprocessed.sv")
}
