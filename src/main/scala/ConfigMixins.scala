//******************************************************************************
// Copyright (c) 2015 - 2018, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

package biriscv

import chisel3._
import chisel3.util.{log2Up}

import freechips.rocketchip.config.{Parameters, Config, Field}
import freechips.rocketchip.subsystem.{SystemBusKey, RocketTilesKey, RocketCrossingParams}
import freechips.rocketchip.devices.tilelink.{BootROMParams}
import freechips.rocketchip.diplomacy.{SynchronousCrossing, AsynchronousCrossing, RationalCrossing}
import freechips.rocketchip.rocket._
import freechips.rocketchip.tile._

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
class WithNBiRISCVCores(n: Int) extends Config(
  new WithNormalBiRISCVSys ++
  new Config((site, here, up) => {
    case BiRISCVTilesKey => {
      List.tabulate(n)(i => BiRISCVTileParams(hartId = i))
    }
  })
)

/**
 * Setup default BiRISCV parameters.
 */
class WithNormalBiRISCVSys extends Config((site, here, up) => {
  case SystemBusKey => up(SystemBusKey, site).copy(beatBytes = 8)
  case XLen => 64
  case MaxHartIdBits => log2Up(site(BiRISCVilesKey).size)
})
