/**************************************************************************************
* Copyright (c) 2021 Li Shi
*
* Zhoushan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*             http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND, EITHER
* EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT, MERCHANTABILITY OR
* FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package zhoushan

import chisel3._
import chisel3.util._
import difftest._
import connect_axi._

class SimTop extends Module {
  val io = IO(new Bundle {
    val logCtrl = new LogCtrlIO
    val perfInfo = new PerfInfoIO
    val uart = new UARTIO
    val memAXI_0 = new AxiIO
  })

  val core = Module(new Core)

  val crossbar = Module(new CoreBusCrossbarNto1(4))
  crossbar.io.in <> core.io.core_bus

  val core2axi = Module(new CoreBus2Axi(new AxiIO))
  core2axi.in <> crossbar.io.out

  /* Original topology
   *   +------+            +---------------------+
   *   | Core | <--AXI4--> | DRAM (SW-simulated) |
   *   +------+            +---------------------+
   * 
   * New topology under test
   *   +------+            +---------------------+
   *   | Core | <--AXI4--> | M0               S0 | <--AXI4-+
   *   +------+            |                     |         |
   *                       |       CONNECT    M1 | <-------+
   *                       |                     |            +---------------------+
   *                       |                  S1 | <--AXI4--> | DRAM (SW-simulated) |
   *                       +---------------------+            +---------------------+
   */

  val network_configs = GetImplicitNetworkConfigs("AXI4")
  val network = Module(new NetworkAXI4Wrapper()(network_configs))
  network.io.clock_noc := clock
  network.io.master(0) <> core2axi.out
  network.io.slave(0) <> network.io.master(1)
  network.io.slave(1) <> io.memAXI_0

  network.io.master(0).aw.bits.asInstanceOf[AXI4ChannelA].user := Cat(core2axi.io.out.aw.bits.user, 2.U(2.W))
  network.io.master(0).ar.bits.asInstanceOf[AXI4ChannelA].user := Cat(core2axi.io.out.ar.bits.user, 2.U(2.W))
  network.io.master(1).aw.bits.asInstanceOf[AXI4ChannelA].user := Cat(core2axi.io.out.aw.bits.user, 3.U(2.W))
  network.io.master(1).ar.bits.asInstanceOf[AXI4ChannelA].user := Cat(core2axi.io.out.ar.bits.user, 3.U(2.W))

  io.uart.out.valid := false.B
  io.uart.out.ch := 0.U
  io.uart.in.valid := false.B
}
