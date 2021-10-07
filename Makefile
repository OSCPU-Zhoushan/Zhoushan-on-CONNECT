BUILD_DIR = ./build
ZHOUSHAN_HOME = $(shell pwd)

default: sim-verilog

sim-verilog:
	mkdir -p $(BUILD_DIR)
	mill -i Zhoushan.runMain zhoushan.TopMain -td $(BUILD_DIR)

emu: sim-verilog
	sed -i 's/io_memAXI_0_w_bits_data,/io_memAXI_0_w_bits_data[3:0],/g' ./build/SimTop.v
	sed -i 's/io_memAXI_0_r_bits_data,/io_memAXI_0_r_bits_data[3:0],/g' ./build/SimTop.v
	sed -i 's/io_memAXI_0_w_bits_data =/io_memAXI_0_w_bits_data[0] =/g' ./build/SimTop.v
	sed -i 's/ io_memAXI_0_r_bits_data;/ io_memAXI_0_r_bits_data[0];/g' ./build/SimTop.v
	sed -i 's/$$fwrite/$$fflush; $$fwrite/g' ./build/SimTop.v
	cd $(ZHOUSHAN_HOME)/difftest && $(MAKE) WITH_DRAMSIM3=1 EMU_TRACE=1 emu -j

emu-direct:
	cd $(ZHOUSHAN_HOME)/difftest && $(MAKE) WITH_DRAMSIM3=1 EMU_TRACE=1 emu -j

soc: sim-verilog
	/bin/bash ./test.sh -l

help:
	mill -i Zhoushan.runMain zhoushan.TopMain --help

clean:
	-rm -rf $(BUILD_DIR)

.PHONY: clean
