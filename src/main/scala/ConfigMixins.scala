//******************************************************************************
// Copyright (c) 2015 - 2018, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

package biriscv

import chisel3._
import chisel3.util.{log2Up}

//import freechips.rocketchip.config.{Parameters, Config, Field}
//import freechips.rocketchip.subsystem.{SystemBusKey, RocketTilesKey, RocketCrossingParams}
import freechips.rocketchip.devices.tilelink.{BootROMParams}
//import freechips.rocketchip.diplomacy.{SynchronousCrossing, AsynchronousCrossing, RationalCrossing}
import freechips.rocketchip.rocket._
import freechips.rocketchip.tile._

import org.chipsalliance.cde.config.{Parameters, Config, Field}
//import freechips.rocketchip.subsystem.{SystemBusKey, RocketCrossingParams}
import freechips.rocketchip.subsystem._
import freechips.rocketchip.prci.{SynchronousCrossing, AsynchronousCrossing, RationalCrossing}
//import org.chipsalliance.rocketconfig.{SynchronousCrossing, AsynchronousCrossing, RationalCrossing}

case object BiRISCVCrossingKey extends Field[Seq[RocketCrossingParams]](List(RocketCrossingParams()))

/**
 * Enable trace port
 */
class WithBiRISCVEnableTrace extends Config((site, here, up) => {
  case BiRISCVTilesKey => up(BiRISCVTilesKey) map (tile => tile.copy(trace = true))
})

/**
 * Makes cacheable region include to/from host addresses.
 * Speeds up operation... at the expense of not being able to use
 * to/fromhost communication unless those lines are evicted from L1.
 */
class WithToFromHostCaching extends Config((site, here, up) => {
  case BiRISCVTilesKey => up(BiRISCVTilesKey, site) map { a =>
    a.copy(core = a.core.copy(
      enableToFromHostCaching = true
    ))
  }
})

/**
 * Create multiple copies of a BiRISCV tile (and thus a core).
 * Override with the default mixins to control all params of the tiles.
 *
 * @param n amount of tiles to duplicate
 */
//class WithNBiRISCVCores(n: Int) extends Config(
//  new WithNormalBiRISCVSys ++
//  new Config((site, here, up) => {
//    case BiRISCVTilesKey => {
////      List.tabulate(n)(i => BiRISCVTileParams(hartId = i))
//      List.tabulate(n)(i => BiRISCVTileParams(tileId = i))
//    }
//  })
//)

// Списано с Ibex
class WithNBiRISCVCores(n: Int = 1) extends Config((site, here, up) => {
  case TilesLocated(InSubsystem) => {
    val prev = up(TilesLocated(InSubsystem), site)
    val idOffset = up(NumTiles)
    (0 until n).map { i =>
      BiRISCVTileAttachParams(
        tileParams = BiRISCVTileParams(tileId = i + idOffset),
        crossingParams = RocketCrossingParams()
      )
    } ++ prev
  }
  case SystemBusKey => up(SystemBusKey, site).copy(beatBytes = 8)
  case MaxHartIdBits => log2Up(site(BiRISCVTilesKey).size)
  case NumTiles => up(NumTiles) + n
})

/**
 * Setup default BiRISCV parameters.
 */
//class WithNormalBiRISCVSys extends Config((site, here, up) => {
//  case SystemBusKey => up(SystemBusKey, site).copy(beatBytes = 8)
////  case XLen => 64
//  case MaxHartIdBits => log2Up(site(BiRISCVTilesKey).size)
//})

// Для chipyard 1.13.0
//class WithDefaultHarnessClockInstantiator extends Config((site, here, up) => {
//  case HarnessClockInstantiatorKey => ClockInstantiator()
//})
