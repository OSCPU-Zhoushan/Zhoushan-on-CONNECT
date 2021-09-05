package zhoushan

import chisel3._
import chisel3.util._
import chisel3.util.experimental._
import zhoushan.Constant._

abstract class AbstractLsuIO extends Bundle {
  val uop = Input(new MicroOp)
  val in1 = Input(UInt(64.W))
  val in2 = Input(UInt(64.W))
  val out = Output(UInt(64.W))
  val busy = Output(Bool())
  val dmem : MemIO
  val intr = Input(Bool())
}

class LsuIO extends AbstractLsuIO {
  override val dmem = new CacheBusIO
}

class LsuWithRamHelperIO extends AbstractLsuIO {
  override val dmem = Flipped(new RamIO)
}

abstract class LsuModule extends Module {
  val io : Bundle
}

// todo: 1) memory address aligned
//       2) clear input when stall from IF stage
class Lsu extends LsuModule with Ext {
  val io = IO(new LsuIO)

  val uop = io.uop
  val reg_uop = RegInit(0.U.asTypeOf(new MicroOp))
  val in1 = io.in1
  val in2 = io.in2
  val is_mem = (uop.fu_code === FU_MEM)
  val reg_is_load = (reg_uop.mem_code === MEM_LD || uop.mem_code === MEM_LDU)
  val reg_is_store = (reg_uop.mem_code === MEM_ST)

  val s_idle :: s_req :: s_wait_r :: s_wait_w :: s_complete :: Nil = Enum(5)
  val state = RegInit(s_idle)

  val init = RegInit(true.B)
  when (init) {
    state := s_idle
    init := false.B
  }

  val req = io.dmem.req
  val resp = io.dmem.resp

  val addr = (in1 + signExt32_64(uop.imm))(31, 0)
  val addr_offset = addr(2, 0)
  val wdata = in2
  val reg_addr = RegInit(0.U(32.W))
  val reg_addr_offset = reg_addr(2, 0)
  val reg_wdata = RegInit(0.U(64.W))

  val mask = MuxLookup(reg_addr_offset, 0.U, Array(
    "b000".U -> "b11111111".U(8.W),
    "b001".U -> "b11111110".U(8.W),
    "b010".U -> "b11111100".U(8.W),
    "b011".U -> "b11111000".U(8.W),
    "b100".U -> "b11110000".U(8.W),
    "b101".U -> "b11100000".U(8.W),
    "b110".U -> "b11000000".U(8.W),
    "b111".U -> "b10000000".U(8.W)
  ))
  val wmask = MuxLookup(reg_uop.mem_size, 0.U, Array(
    MEM_BYTE  -> "b00000001".U(8.W),
    MEM_HALF  -> "b00000011".U(8.W),
    MEM_WORD  -> "b00001111".U(8.W),
    MEM_DWORD -> "b11111111".U(8.W)
  ))

  // val wmask64 = Cat(Fill(8, wmask(7)), Fill(8, wmask(6)),
  //                   Fill(8, wmask(5)), Fill(8, wmask(4)),
  //                   Fill(8, wmask(3)), Fill(8, wmask(2)),
  //                   Fill(8, wmask(1)), Fill(8, wmask(0)))
  // val in2_masked = in2 & wmask64

  req.bits.addr := Cat(reg_addr(31, 3), Fill(3, 0.U))
  req.bits.ren := reg_is_load
  req.bits.wdata := (reg_wdata << (reg_addr_offset << 3))(63, 0)
  req.bits.wmask := mask & ((wmask << reg_addr_offset)(7, 0))
  req.bits.wen := reg_is_store
  req.bits.user := 0.U
  req.valid := uop.valid && (state === s_req) &&
               (reg_is_load || reg_is_store) && !io.intr

  resp.ready := (state === s_wait_r) || (state === s_wait_w)

  /* FSM to handle CoreBus bus status
   *
   *  Simplified FSM digram
   *
   *       ┌───────────────────────────────────────────────────┐
   *       │                                                   │
   *       │                  !resp_success                    │
   *       │                        ┌─┐                        │
   *       v                        | v                        │
   *   ┌────────┐   reg_is_load  ┌──────────┐  resp_success    │
   *   │ s_idle │    ┌─────────> │ s_wait_r │ ──┐              │
   *   └────────┘    │           └──────────┘   │              │
   *       |         │                          │    ┌────────────┐
   *       |         │        !resp_success     ├──> │ s_complete │
   *       |         │               ┌─┐        │    └────────────┘
   *       v         │               | v        │
   *   ┌────────┐    │           ┌──────────┐   │
   *   │ s_req  │ ───┴─────────> │ s_wait_w │ ──┘
   *   └────────┘   reg_is_store └──────────┘  resp_success
   *
   */

  val load_data = RegInit(UInt(64.W), 0.U)
  val resp_success = resp.fire()

  switch (state) {
    is (s_idle) {
      when (is_mem & !io.intr) {
        state := s_req
        reg_uop := uop
        reg_addr := addr
        reg_wdata := wdata
      }
    }
    is (s_req) {
      when (io.intr) {
        state := s_idle
      } .elsewhen (reg_is_load && req.fire()) {
        state := s_wait_r
      } .elsewhen (reg_is_store && req.fire()) {
        state := s_wait_w
      }
    }
    is (s_wait_r) {
      when (resp_success) {
        load_data := resp.bits.rdata >> (reg_addr_offset << 3)
        state := s_complete
        if (Settings.DebugMsgLsu) {
          printf("%d: [LD] pc=%x addr=%x rdata=%x -> %x\n", DebugTimer(), uop.pc, reg_addr, resp.bits.rdata, load_data)
        }
      }
    }
    is (s_wait_w) {
      when (resp_success) {
        state := s_complete
        if (Settings.DebugMsgLsu) {
          printf("%d: [ST] pc=%x addr=%x wdata=%x -> %x wmask=%x\n", DebugTimer(), uop.pc, reg_addr, in2, req.bits.wdata, req.bits.wmask)
        }
      }
    }
    is (s_complete) {
      state := s_idle
      reg_uop := 0.U.asTypeOf(new MicroOp)
      reg_addr := 0.U
    }
  }

  BoringUtils.addSource(RegNext(RegNext(reg_addr)), "lsu_addr")

  val ld_out = Wire(UInt(64.W))
  val ldu_out = Wire(UInt(64.W))
  val load_out = Wire(UInt(64.W))

  ld_out := Mux(reg_uop.mem_code === MEM_LD, MuxLookup(reg_uop.mem_size, 0.U, Array(
    MEM_BYTE  -> Cat(Fill(56, load_data(7)), load_data(7, 0)),
    MEM_HALF  -> Cat(Fill(48, load_data(15)), load_data(15, 0)),
    MEM_WORD  -> Cat(Fill(32, load_data(31)), load_data(31, 0)),
    MEM_DWORD -> load_data
  )), 0.U)

  ldu_out := Mux(reg_uop.mem_code === MEM_LDU, MuxLookup(reg_uop.mem_size, 0.U, Array(
    MEM_BYTE  -> Cat(Fill(56, 0.U), load_data(7, 0)),
    MEM_HALF  -> Cat(Fill(48, 0.U), load_data(15, 0)),
    MEM_WORD  -> Cat(Fill(32, 0.U), load_data(31, 0)),
    MEM_DWORD -> load_data
  )), 0.U)

  load_out := MuxLookup(reg_uop.mem_code, 0.U, Array(
    MEM_LD  -> ld_out,
    MEM_LDU -> ldu_out
  ))

  io.out := Mux(state === s_complete, load_out, 0.U)
  io.busy := ((state === s_idle) && is_mem) || (state === s_req) ||
             (state === s_wait_r) || (state === s_wait_w)

  // raise an addr_unaligned exception
  //    half  -> offset = 111
  //    word  -> offset = 101/110/111
  //    dword -> offset != 000
  val addr_unaligned = RegInit(false.B)
  addr_unaligned := Mux(uop.fu_code === FU_MEM, 
    MuxLookup(uop.mem_size, false.B, Array(
      MEM_HALF  -> (addr_offset === "b111".U),
      MEM_WORD  -> (addr_offset.asUInt() > "b100".U),
      MEM_DWORD -> (addr_offset =/= "b000".U)
    )), false.B)
  // todo: add this exception in CSR unit

}

class LsuWithRamHelper extends LsuModule with Ext {
  val io = IO(new LsuWithRamHelperIO)

  val uop = io.uop
  val in1 = io.in1
  val in2 = io.in2

  val addr = in1 + signExt32_64(uop.imm)
  val addr_offset = addr(2, 0)
  val addr_nextline = addr + "b1000".U
  val addr_offset_nextline = (~addr_offset) + 1.U;

  val mask = MuxLookup(addr_offset, 0.U, Array(
    "b000".U -> "hffffffffffffffff".U,
    "b001".U -> "hffffffffffffff00".U,
    "b010".U -> "hffffffffffff0000".U,
    "b011".U -> "hffffffffff000000".U,
    "b100".U -> "hffffffff00000000".U,
    "b101".U -> "hffffff0000000000".U,
    "b110".U -> "hffff000000000000".U,
    "b111".U -> "hff00000000000000".U
  ))
  val mask_nextline = ~mask;
  val wmask = MuxLookup(uop.mem_size, 0.U, Array(
    MEM_BYTE  -> "h00000000000000ff".U,
    MEM_HALF  -> "h000000000000ffff".U,
    MEM_WORD  -> "h00000000ffffffff".U,
    MEM_DWORD -> "hffffffffffffffff".U
  ))

  val load_data = Wire(UInt(64.W))
  val load_data_reg = RegNext(load_data)

  // may need to read/write memory in 2 lines
  val stall = RegInit(false.B)
  // half  -> offset = 111
  // word  -> offset = 101/110/111
  // dword -> offset != 000
  stall := Mux(uop.fu_code === FU_MEM, MuxLookup(uop.mem_size, false.B, Array(
    MEM_HALF  -> (addr_offset === "b111".U),
    MEM_WORD  -> (addr_offset.asUInt() > "b100".U),
    MEM_DWORD -> (addr_offset =/= "b000".U)
  )), false.B)

  when (stall) {
    stall := false.B
  }

  // 0 = normal / read line 1, 1 = read line 2
  val dmem_state = RegInit(0.U(1.W))
  when (dmem_state === 0.U) {
    when (stall) { dmem_state := 1.U }
    io.dmem.addr := addr
    load_data := io.dmem.rdata >> (addr_offset << 3)
    io.dmem.wmask := mask & ((wmask << (addr_offset << 3))(63, 0))
    io.dmem.wdata := (in2 << (addr_offset << 3))(63, 0)
  } .otherwise {
    io.dmem.addr := addr_nextline
    load_data := load_data_reg | (io.dmem.rdata << (addr_offset_nextline << 3))
    io.dmem.wmask := mask_nextline & (wmask >> (addr_offset_nextline << 3)).asUInt()
    io.dmem.wdata := (in2 >> (addr_offset_nextline << 3)).asUInt()
  }

  io.dmem.en := (uop.fu_code === FU_MEM)
  io.dmem.wen := (uop.mem_code === MEM_ST)

  val ld_out = Wire(UInt(64.W))
  val ldu_out = Wire(UInt(64.W))
  val load_out = Wire(UInt(64.W))

  ld_out := Mux(uop.mem_code === MEM_LD, MuxLookup(uop.mem_size, 0.U, Array(
    MEM_BYTE  -> Cat(Fill(56, load_data(7)), load_data(7, 0)),
    MEM_HALF  -> Cat(Fill(48, load_data(15)), load_data(15, 0)),
    MEM_WORD  -> Cat(Fill(32, load_data(31)), load_data(31, 0)),
    MEM_DWORD -> load_data
  )), 0.U)

  ldu_out := Mux(uop.mem_code === MEM_LDU, MuxLookup(uop.mem_size, 0.U, Array(
    MEM_BYTE  -> Cat(Fill(56, 0.U), load_data(7, 0)),
    MEM_HALF  -> Cat(Fill(48, 0.U), load_data(15, 0)),
    MEM_WORD  -> Cat(Fill(32, 0.U), load_data(31, 0)),
    MEM_DWORD -> load_data
  )), 0.U)

  load_out := MuxLookup(uop.mem_code, 0.U, Array(
    MEM_LD  -> ld_out,
    MEM_LDU -> ldu_out
  ))

  io.out := load_out
  io.busy := stall
}
