package chipyard.fpga.arty

import chisel3._

import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.devices.debug._
import freechips.rocketchip.jtag.{JTAGIO}
import freechips.rocketchip.subsystem._

import chipyard.iobinders.GetSystemParameters

import sifive.blocks.devices.uart._
import sifive.blocks.devices.jtag._
import sifive.blocks.devices.spi._
import sifive.blocks.devices.pinctrl._

import sifive.fpgashells.ip.xilinx.{IBUFG, IOBUF, PULLUP, PowerOnResetFPGAOnly}

import chipyard.harness.{ComposeHarnessBinder, OverrideHarnessBinder}
import chipyard.iobinders.JTAGChipIO

import testchipip._

import chisel3.util._
import chisel3.experimental.IO
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.devices.debug.HasPeripheryDebug
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._
import freechips.rocketchip.prci.{ClockSinkDomain}
import scala.math.min

class WithArtyResetHarnessBinder extends ComposeHarnessBinder({
  (system: HasPeripheryDebugModuleImp, th: ArtyFPGATestHarness, ports: Seq[Bool]) => {
    require(ports.size == 2)

    withClockAndReset(th.clock_32MHz, th.hReset) {
      // Debug module reset
      th.dut_ndreset := ports(0)

      // JTAG reset
      ports(1) := PowerOnResetFPGAOnly(th.buildtopClock)
    }
  }
})

class WithFPGASimSerial extends OverrideHarnessBinder({
  (system: CanHavePeripheryTLSerial, th: ArtyFPGATestHarness, ports: Seq[ClockedIO[SerialIO]]) => {
    // This binder is the main difference between FPGA and Chipyard simulation.
    // For FPGA sim, we want to wire the output of the Xilinx reset IP to 
    // the sim ram and serial modules, rather than connect the harness reset
    // directly.
    implicit val p = chipyard.iobinders.GetSystemParameters(system)
    ports.map({ port =>
      val bits = SerialAdapter.asyncQueue(port, th.buildtopClock, th.buildtopReset)
      withClockAndReset(th.buildtopClock, th.buildtopReset.asBool) {
        val ram = SerialAdapter.connectHarnessRAM(system.serdesser.get, bits, th.buildtopReset.asBool)

        val success = {
          val sim = Module(new SimSerial(ram.module.io.tsi_ser.w))
          sim.io.clock := port.clock
          sim.io.reset := th.buildtopReset.asBool
          sim.io.serial <> ram.module.io.tsi_ser
          sim.io.exit
        }

        when (success) { th.success := true.B }
      }
    })
  }
})

class WithArtyJTAGHarnessBinder extends OverrideHarnessBinder({
  (system: HasPeripheryDebug, th: ArtyFPGATestHarness, ports: Seq[Data]) => {
    ports.map {
      case j: JTAGChipIO =>
        withClockAndReset(th.buildtopClock, th.hReset) {
          val jtag_wire = Wire(new JTAGIO)
          jtag_wire.TDO.data := j.TDO
          jtag_wire.TDO.driven := true.B
          j.TCK := jtag_wire.TCK
          j.TMS := jtag_wire.TMS
          j.TDI := jtag_wire.TDI

          val io_jtag = Wire(new JTAGPins(() => new BasePin(), false)).suggestName("jtag")

          JTAGPinsFromPort(io_jtag, jtag_wire)

          io_jtag.TCK.i.ival := IBUFG(IOBUF(th.jd_2).asClock).asBool

          IOBUF(th.jd_5, io_jtag.TMS)
          PULLUP(th.jd_5)

          IOBUF(th.jd_4, io_jtag.TDI)
          PULLUP(th.jd_4)

          IOBUF(th.jd_0, io_jtag.TDO)

          // mimic putting a pullup on this line (part of reset vote)
          th.SRST_n := IOBUF(th.jd_6)
          PULLUP(th.jd_6)

          // ignore the po input
          io_jtag.TCK.i.po.map(_ := DontCare)
          io_jtag.TDI.i.po.map(_ := DontCare)
          io_jtag.TMS.i.po.map(_ := DontCare)
          io_jtag.TDO.i.po.map(_ := DontCare)
        }
    }
  }
})

class WithArtyUARTHarnessBinder extends OverrideHarnessBinder({
  (system: HasPeripheryUARTModuleImp, th: ArtyFPGATestHarness, ports: Seq[UARTPortIO]) => {
    withClockAndReset(th.clock_32MHz, th.hReset) {
      IOBUF(th.uart_rxd_out,  ports.head.txd)
      ports.head.rxd := IOBUF(th.uart_txd_in)
    }
  }
})

class WithArtySPIFlashHarnessBinder extends OverrideHarnessBinder({
  (system: HasPeripherySPIFlashModuleImp, th: ArtyFPGATestHarness, ports: Seq[SPIPortIO]) => {
    withClockAndReset(th.buildtopClock, th.buildtopReset) {
      implicit val p: Parameters = GetSystemParameters(system)

      val io_qspi = Wire(new SPIPins(() => new BasePin(), p(PeripherySPIFlashKey)(0))).suggestName("qspi")

      SPIPinsFromPort(io_qspi, ports(0), clock = th.buildtopClock, reset = th.buildtopReset.asBool, syncStages = 3)

      IOBUF(th.qspi_cs, io_qspi.cs(0))
      IOBUF(th.qspi_sck, io_qspi.sck)
      IOBUF(th.qspi_dq(0), io_qspi.dq(0))
      IOBUF(th.qspi_dq(1), io_qspi.dq(1))
      IOBUF(th.qspi_dq(2), io_qspi.dq(2))
      IOBUF(th.qspi_dq(3), io_qspi.dq(3))

      // ignore the po input
      io_qspi.cs(0).i.po.map(_ := DontCare)
      io_qspi.sck.i.po.map(_ := DontCare)
      io_qspi.dq(0).i.po.map(_ := DontCare)
      io_qspi.dq(1).i.po.map(_ := DontCare)
      io_qspi.dq(2).i.po.map(_ := DontCare)
      io_qspi.dq(3).i.po.map(_ := DontCare)
    }
  }
})
