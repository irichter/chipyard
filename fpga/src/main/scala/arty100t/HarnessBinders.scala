package chipyard.fpga.arty100t

import chisel3._
import chisel3.experimental.{BaseModule}

import freechips.rocketchip.util.{HeterogeneousBag}
import freechips.rocketchip.tilelink.{TLBundle}

import sifive.blocks.devices.uart.{HasPeripheryUARTModuleImp, UARTPortIO}
import sifive.blocks.devices.spi.{HasPeripherySPI, SPIPortIO}

import chipyard.{HasHarnessSignalReferences, CanHaveMasterTLMemPort}
import chipyard.harness.{OverrideHarnessBinder}

import testchipip._
import sifive.blocks.devices.spi.HasPeripherySPIFlashModuleImp
import freechips.rocketchip.devices.debug.HasPeripheryDebug
import chipyard.iobinders.JTAGChipIO
import freechips.rocketchip.devices.debug.ClockedDMIIO
import freechips.rocketchip.devices.debug.ClockedAPBBundle
import sifive.blocks.devices.gpio.HasPeripheryGPIO

/*** UART ***/
class WithUART extends OverrideHarnessBinder({
  (system: HasPeripheryUARTModuleImp, th: BaseModule with HasHarnessSignalReferences, ports: Seq[UARTPortIO]) => {
    th match { case arty100tth: Arty100TFPGATestHarnessImp => {
      arty100tth.arty100tOuter.io_uart_bb.bundle <> ports.head
    } }
  }
})

/*** SPI ***/
class WithSPISDCard extends OverrideHarnessBinder({
  (system: HasPeripherySPI, th: BaseModule with HasHarnessSignalReferences, ports: Seq[SPIPortIO]) => {
    th match { case arty100tth: Arty100TFPGATestHarnessImp => {
      arty100tth.arty100tOuter.io_spi_bb.bundle <> ports.head
    } }
  }
})

class WithSPIFlash extends OverrideHarnessBinder({
  (system: HasPeripherySPIFlashModuleImp, th: BaseModule with HasHarnessSignalReferences, ports: Seq[SPIPortIO]) => {
    th match { case arty100tth: Arty100TFPGATestHarnessImp => {
      arty100tth.arty100tOuter.io_spiflash_bb.bundle <> ports.head
    } }
  }
})

class WithJTAGDebug extends OverrideHarnessBinder({
  (system: HasPeripheryDebug, th: BaseModule with HasHarnessSignalReferences, ports: Seq[Data]) => {
    th match { case arty100tth: Arty100TFPGATestHarnessImp => {
    ports.map {
      case j: JTAGChipIO =>
        j.TCK := arty100tth.arty100tOuter.debug_jtag.TCK
        j.TMS := arty100tth.arty100tOuter.debug_jtag.TMS
        j.TDI := arty100tth.arty100tOuter.debug_jtag.TDI
        arty100tth.arty100tOuter.debug_jtag.TDO.data := j.TDO
        arty100tth.arty100tOuter.debug_jtag.TDO.driven := true.B
      case d: ClockedDMIIO =>
        d.dmi.req.valid := false.B
        d.dmi.req.bits  := DontCare
        d.dmi.resp.ready := true.B
        d.dmiClock := false.B.asClock
        d.dmiReset := true.B
      case a: ClockedAPBBundle =>
        a.tieoff()
        a.clock := false.B.asClock
        a.reset := true.B.asAsyncReset
        a.psel := false.B
        a.penable := false.B
    } } }
  }
})

/*** Experimental DDR ***/
class WithDDRMem extends OverrideHarnessBinder({
  (system: CanHaveMasterTLMemPort, th: BaseModule with HasHarnessSignalReferences, ports: Seq[HeterogeneousBag[TLBundle]]) => {
    th match { case arty100tth: Arty100TFPGATestHarnessImp => {
      require(ports.size == 1)

      val bundles = arty100tth.arty100tOuter.ddrClient.out.map(_._1)
      val ddrClientBundle = Wire(new HeterogeneousBag(bundles.map(_.cloneType)))
      bundles.zip(ddrClientBundle).foreach { case (bundle, io) => bundle <> io }
      ddrClientBundle <> ports.head
    } }
  }
})

class WithFPGASimSerial extends OverrideHarnessBinder({
  (system: CanHavePeripheryTLSerial, th: BaseModule with HasHarnessSignalReferences, ports: Seq[ClockedIO[SerialIO]]) => {
    implicit val p = chipyard.iobinders.GetSystemParameters(system)
    th match { case arty100tth: Arty100TFPGATestHarnessImp => {
      ports.map({ port =>
        val bits = SerialAdapter.asyncQueue(port, arty100tth.buildtopClock, arty100tth.buildtopReset)
        withClockAndReset(arty100tth.buildtopClock, arty100tth.buildtopReset.asBool) {
          val ram = SerialAdapter.connectHarnessRAM(system.serdesser.get, bits, arty100tth.buildtopReset.asBool)

          val success = {
            val sim = Module(new SimSerial(ram.module.io.tsi_ser.w))
            sim.io.clock := port.clock
            sim.io.reset := arty100tth.buildtopReset.asBool
            sim.io.serial <> ram.module.io.tsi_ser
            sim.io.exit
          }

          when (success) { arty100tth.success := true.B }
        }
      })
    } }
  }
})
