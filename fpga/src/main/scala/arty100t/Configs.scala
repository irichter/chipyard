package chipyard.fpga.arty100t

import sys.process._

import freechips.rocketchip.config.{Config, Parameters}
import freechips.rocketchip.subsystem.{SystemBusKey, PeripheryBusKey, ControlBusKey, ExtMem}
import freechips.rocketchip.devices.debug.{DebugModuleKey, ExportDebug, JTAG}
import freechips.rocketchip.devices.tilelink.{DevNullParams, BootROMLocated}
import freechips.rocketchip.diplomacy.{DTSModel, DTSTimebase, RegionType, AddressSet}
import freechips.rocketchip.tile.{XLen}

import freechips.rocketchip.subsystem.{MemoryBusKey}

import sifive.blocks.devices.spi.{PeripherySPIKey, SPIParams}
import sifive.blocks.devices.uart.{PeripheryUARTKey, UARTParams}

import sifive.fpgashells.shell.{DesignKey}
import sifive.fpgashells.shell.xilinx.{ArtyDDRSize}

import testchipip.{SerialTLKey}

import chipyard.{BuildSystem, ExtTLMem, DefaultClockFrequencyKey}
import freechips.rocketchip.subsystem.{WithL1ICacheWays,WithL1ICacheSets,WithL1DCacheWays,WithL1DCacheSets,WithDefaultBtb,RocketTilesKey}
import freechips.rocketchip.devices.debug.JtagDTMKey
import freechips.rocketchip.devices.debug.JtagDTMConfig
import sifive.blocks.devices.spi.PeripherySPIFlashKey
import sifive.blocks.devices.spi.SPIFlashParams
import freechips.rocketchip.subsystem.WithNBanks
import testchipip.WithBackingScratchpad
import sifive.blocks.devices.mockaon.PeripheryMockAONKey
import sifive.blocks.devices.mockaon.MockAONParams
import sifive.blocks.devices.gpio.PeripheryGPIOKey
import sifive.blocks.devices.gpio.GPIOParams

class WithDefaultPeripherals extends Config((site, here, up) => {
  case PeripheryUARTKey => List(UARTParams(address = BigInt(0x64000000L)))
  case PeripherySPIKey => List(SPIParams(rAddress = BigInt(0x64001000L)))
  case PeripherySPIFlashKey => List()
})

class WithSystemModifications extends Config((site, here, up) => {
  case DTSTimebase => BigInt((1e6).toLong)
  case BootROMLocated(x) => up(BootROMLocated(x), site).map { p =>
    // invoke makefile for sdboot
    val freqMHz = (site(DefaultClockFrequencyKey) * 1e6).toLong
    val make = s"make -C fpga/src/main/resources/arty100t/sdboot PBUS_CLK=${freqMHz} bin"
    require (make.! == 0, "Failed to build bootrom")
    p.copy(hang = 0x10000, contentFileName = s"./fpga/src/main/resources/arty100t/sdboot/build/sdboot.bin")
  }
  case ExtMem => up(ExtMem, site).map(x => x.copy(master = x.master.copy(size = site(ArtyDDRSize)))) // set extmem to DDR size
  case SerialTLKey => None // remove serialized tl port
})

// DOC include start: AbstractArty100T and Rocket
class WithArty100TTweaks extends Config(
  // harness binders
  new WithUART ++
  new WithSPISDCard ++
  new WithDDRMem ++
  // io binders
  new WithUARTIOPassthrough ++
  new WithSPIIOPassthrough ++
  new WithTLIOPassthrough ++
  // other configuration
  new WithDefaultPeripherals ++
  new chipyard.config.WithTLBackingMemory ++ // use TL backing memory
  new WithSystemModifications ++ // setup busses, use sdboot bootrom, setup ext. mem. size
  new chipyard.config.WithNoDebug ++ // remove debug module
  new freechips.rocketchip.subsystem.WithoutTLMonitors ++
  new freechips.rocketchip.subsystem.WithNMemoryChannels(1) ++
  new WithFPGAFrequency(100) // default 100MHz freq
)

class RocketArty100TConfig extends Config(
  // reduce L2 size to fit in 100T's BRAMs
  new freechips.rocketchip.subsystem.WithInclusiveCache(capacityKB=256) ++
  // with reduced cache size, closes timing at 50 MHz
  new WithFPGAFrequency(50) ++
  new WithArty100TTweaks ++
  new chipyard.RocketConfig)
// DOC include end: AbstractArty100T and Rocket

class RocketArty100TSimConfig extends Config(
   new WithFPGASimSerial ++
   new testchipip.WithDefaultSerialTL ++
   new chipyard.harness.WithSimSerial ++
   new chipyard.harness.WithTiedOffDebug ++
   new RocketArty100TConfig)

class WithL1DCacheNoScratchpad extends Config((site, here, up) => {
  case RocketTilesKey => up(RocketTilesKey, site) map { r =>
    r.copy(dcache = r.dcache.map(_.copy(scratch = None))) }
})

class With64BitExtMemDataWidth extends Config((site, here, up) => {
  case ExtMem => up(ExtMem, site).map(x => x.copy(master = x.master.copy(beatBytes = 8)))
})


class WithE300Peripherals extends Config((site, here, up) => {
  case PeripheryUARTKey => List(
    UARTParams(address = 0x10013000, dataBits = 8, stopBits = 1, initBaudRate = 115200))
  case JtagDTMKey => new JtagDTMConfig (
    idcodeVersion = 2,
    idcodePartNum = 0x000,
    idcodeManufId = 0x489,
    debugIdleCycles = 5)
  case PeripherySPIFlashKey => List(
    SPIFlashParams(
      fAddress = 0x20000000,
      rAddress = 0x10014000,
      defaultSampleDel = 3))
  case PeripherySPIKey => List()
})

class WithE300Modifications  extends Config((site, here, up) => {
  case DTSTimebase => BigInt(32768)
  case BootROMLocated(x) => up(BootROMLocated(x), site).map { p =>
    // invoke makefile for xip
    val make = s"make -C fpga/src/main/resources/arty/xip bin"
    require (make.! == 0, "Failed to build bootrom")
    p.copy(hang = 0x10000, contentFileName = s"./fpga/src/main/resources/arty/xip/build/xip.bin")
  }
  case ExtMem => up(ExtMem, site).map(x => x.copy(master = x.master.copy(size = site(ArtyDDRSize)))) // set extmem to DDR size
  case SerialTLKey => None // remove serialized tl port
})

class WithNPerfCounters(n: Int) extends Config ((site, here, up) => {
  case RocketTilesKey => up(RocketTilesKey, site) map { r =>
    r.copy(core = r.core.copy(nPerfCounters = n))
  }
})

class WithFPU(fLen: Int = 64) extends Config((site, here, up) => {
  case RocketTilesKey => up(RocketTilesKey, site) map { r =>
    r.copy(core = r.core.copy(fpu = r.core.fpu.map(_.copy(fLen = fLen, minFLen = 32))))
  }
})

class E300Arty100TConfig extends Config(
  // reduce L2 size to just double of L1
  new freechips.rocketchip.subsystem.WithInclusiveCache(capacityKB=96, nWays=6, outerLatencyCycles=20) ++
  // new freechips.rocketchip.subsystem.WithNBanks(2) ++
  new WithFPGAFrequency(50) ++
  new WithBackingScratchpad(0x08000000L, (8 << 10) - 1) ++ //8K scratchpad
  // new chipyard.config.WithMemoryBusFrequency(100) ++
  // new chipyard.config.WithTileFrequency(100) ++
  // new chipyard.config.WithSystemBusFrequency(50) ++
// for lut lookup  new WithNPerfCounters(29) ++
  
  // harness binders
  new WithUART ++
  new WithSPIFlash ++
  new WithJTAGDebug ++
  new WithDDRMem ++
  // io binders
  new WithUARTIOPassthrough ++
  new WithSPIFlashIOPassthrough ++
  new WithTLIOPassthrough ++
  new chipyard.iobinders.WithDebugIOCells ++
  // other configuration
  new WithE300Peripherals ++
  new chipyard.config.WithTLBackingMemory ++ // use TL backing memory
  new WithE300Modifications ++ // setup busses, use SPI bootrom, setup ext. mem. size
  new freechips.rocketchip.subsystem.WithNBreakpoints(8) ++
  new freechips.rocketchip.subsystem.WithoutTLMonitors ++
  new freechips.rocketchip.subsystem.WithNMemoryChannels(1) ++

  new WithL1DCacheWays(4) ++
  new WithL1DCacheSets(256) ++
  new WithL1DCacheNoScratchpad ++
    new With64BitExtMemDataWidth ++
  //Tiny core
  new WithL1ICacheWays(2) ++
  new WithL1ICacheSets(128) ++
  new WithDefaultBtb ++
  new freechips.rocketchip.subsystem.With1TinyCore ++             // single tiny rocket-core
// for lut lookup new freechips.rocketchip.subsystem.With1TinyCoreWithFPU(32, 32) ++             // single tiny rocket-core
  //new WithFPU(64) ++
  new chipyard.config.AbstractConfig
)

class E300Arty100TConfigBigCache extends Config(
  // reduce L2 size to just double of L1
  new freechips.rocketchip.subsystem.WithInclusiveCache(capacityKB=256, nWays=8, outerLatencyCycles=20) ++
  // new freechips.rocketchip.subsystem.WithNBanks(2) ++
  new WithFPGAFrequency(50) ++
  new WithBackingScratchpad(0x08000000L, (8 << 10) - 1) ++ //8K scratchpad
  // new chipyard.config.WithMemoryBusFrequency(100) ++
  // new chipyard.config.WithTileFrequency(100) ++
  // new chipyard.config.WithSystemBusFrequency(50) ++
// for lut lookup  new WithNPerfCounters(29) ++
  
  // harness binders
  new WithUART ++
  new WithSPIFlash ++
  new WithJTAGDebug ++
  new WithDDRMem ++
  // io binders
  new WithUARTIOPassthrough ++
  new WithSPIFlashIOPassthrough ++
  new WithTLIOPassthrough ++
  new chipyard.iobinders.WithDebugIOCells ++
  // other configuration
  new WithE300Peripherals ++
  new chipyard.config.WithTLBackingMemory ++ // use TL backing memory
  new WithE300Modifications ++ // setup busses, use SPI bootrom, setup ext. mem. size
  new freechips.rocketchip.subsystem.WithNBreakpoints(8) ++
  new freechips.rocketchip.subsystem.WithoutTLMonitors ++
  new freechips.rocketchip.subsystem.WithNMemoryChannels(1) ++

  new WithL1DCacheNoScratchpad ++
  new With64BitExtMemDataWidth ++
  //Tiny core
  new WithL1DCacheWays(4) ++
  new WithL1DCacheSets(256) ++
  new WithL1DCacheNoScratchpad ++
    new With64BitExtMemDataWidth ++
  //Tiny core
  new WithL1ICacheWays(2) ++
  new WithL1ICacheSets(128) ++
  new WithDefaultBtb ++
  new freechips.rocketchip.subsystem.With1TinyCore ++             // single tiny rocket-core
// for lut lookup new freechips.rocketchip.subsystem.With1TinyCoreWithFPU(32, 32) ++             // single tiny rocket-core
  //new WithFPU(64) ++
  new chipyard.config.AbstractConfig
)

/*class InclusiveCacheWriteBytes8 extends Config((site, here, up) => {
  case InclusiveCacheKey => up(InclusiveCacheKey, site).map { r =>
    r.copy(writeBytes = 8)
  }
})*/

class With64BitSystemBusDataWidth extends Config((site, here, up) => {
    case SystemBusKey => up(SystemBusKey, site).copy(beatBytes = 8)
    case MemoryBusKey => up(MemoryBusKey, site).copy(beatBytes = 8)
})

class E300Arty100TConfig32D extends Config(
  // reduce L2 size to just double of L1
  // Low outerLatencyCycles to keep MSHRs down for timing
  new freechips.rocketchip.subsystem.WithInclusiveCache(capacityKB=96, nWays=6, writeBytes=8, outerLatencyCycles=20) ++
  new With64BitSystemBusDataWidth ++
  // new freechips.rocketchip.subsystem.WithNBanks(2) ++
  new WithFPGAFrequency(50) ++
  new WithBackingScratchpad(0x08000000L, (8 << 10) - 1) ++ //8K scratchpad
  // new chipyard.config.WithMemoryBusFrequency(100) ++
  // new chipyard.config.WithTileFrequency(100) ++
  // new chipyard.config.WithSystemBusFrequency(50) ++
  new WithNPerfCounters(29) ++
  
  // harness binders
  new WithUART ++
  new WithSPIFlash ++
  new WithJTAGDebug ++
  new WithDDRMem ++
  // io binders
  new WithUARTIOPassthrough ++
  new WithSPIFlashIOPassthrough ++
  new WithTLIOPassthrough ++
  new chipyard.iobinders.WithDebugIOCells ++
  // other configuration
  new WithE300Peripherals ++
  new chipyard.config.WithTLBackingMemory ++ // use TL backing memory
  new WithE300Modifications ++ // setup busses, use SPI bootrom, setup ext. mem. size
  new freechips.rocketchip.subsystem.WithNBreakpoints(8) ++
  new freechips.rocketchip.subsystem.WithoutTLMonitors ++
  new freechips.rocketchip.subsystem.WithNMemoryChannels(1) ++
  
  new WithL1DCacheWays(4) ++
  new WithL1DCacheSets(256) ++
  new WithL1DCacheNoScratchpad ++
    new With64BitExtMemDataWidth ++
  //Tiny core
  new WithL1ICacheWays(2) ++
  new WithL1ICacheSets(128) ++
  new WithDefaultBtb ++
  //new freechips.rocketchip.subsystem.With1TinyCore ++             // single tiny rocket-core
  new freechips.rocketchip.subsystem.With1TinyCoreWithFPU(32, 64) ++             // single tiny rocket-core
  //new WithFPU(64) ++
  new chipyard.config.AbstractConfig
)

class E300Arty100TConfig32DBigCache extends Config(
  // reduce L2 size to just double of L1
  // Low outerLatencyCycles to keep MSHRs down for timing
  // , nWays=6
  new freechips.rocketchip.subsystem.WithInclusiveCache(capacityKB=256, nWays=8, writeBytes=8, outerLatencyCycles=20) ++
  new With64BitSystemBusDataWidth ++
  // new freechips.rocketchip.subsystem.WithNBanks(2) ++
  new WithFPGAFrequency(50) ++
  new WithBackingScratchpad(0x08000000L, (8 << 10) - 1) ++ //8K scratchpad
  // new chipyard.config.WithMemoryBusFrequency(100) ++
  // new chipyard.config.WithTileFrequency(100) ++
  // new chipyard.config.WithSystemBusFrequency(50) ++
  new WithNPerfCounters(29) ++
  
  // harness binders
  new WithUART ++
  new WithSPIFlash ++
  new WithJTAGDebug ++
  new WithDDRMem ++
  // io binders
  new WithUARTIOPassthrough ++
  new WithSPIFlashIOPassthrough ++
  new WithTLIOPassthrough ++
  new chipyard.iobinders.WithDebugIOCells ++
  // other configuration
  new WithE300Peripherals ++
  new chipyard.config.WithTLBackingMemory ++ // use TL backing memory
  new WithE300Modifications ++ // setup busses, use SPI bootrom, setup ext. mem. size
  new freechips.rocketchip.subsystem.WithNBreakpoints(8) ++
  new freechips.rocketchip.subsystem.WithoutTLMonitors ++
  new freechips.rocketchip.subsystem.WithNMemoryChannels(1) ++
  
  new WithL1DCacheWays(4) ++
  new WithL1DCacheSets(256) ++
  new WithL1DCacheNoScratchpad ++
    new With64BitExtMemDataWidth ++
  //Tiny core
  new WithL1ICacheWays(2) ++
  new WithL1ICacheSets(128) ++
  new WithDefaultBtb ++
  //new freechips.rocketchip.subsystem.With1TinyCore ++             // single tiny rocket-core
  new freechips.rocketchip.subsystem.With1TinyCoreWithFPU(32, 64) ++             // single tiny rocket-core
  //new WithFPU(64) ++
  new chipyard.config.AbstractConfig
)

class WithFPGAFrequency(fMHz: Double) extends Config(
  new chipyard.config.WithPeripheryBusFrequency(fMHz) ++ // assumes using PBUS as default freq.
  new chipyard.config.WithMemoryBusFrequency(fMHz)
)

class WithFPGAFreq25MHz extends WithFPGAFrequency(25)
class WithFPGAFreq50MHz extends WithFPGAFrequency(50)
class WithFPGAFreq75MHz extends WithFPGAFrequency(75)
class WithFPGAFreq100MHz extends WithFPGAFrequency(100)
