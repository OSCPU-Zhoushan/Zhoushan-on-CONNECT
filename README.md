# Zhoushan Core

Open Source Chip Project by University (OSCPU) - Team Zhoushan

Team member: Binggang Qiu, Jian Shi, Li Shi, Hanyu Wang, Yanjun Yang

## Dependency

1. [AM](https://github.com/OSCPU-Zhoushan/abstract-machine), branch: zhoushan

1. [AM-Kernels](https://github.com/NJU-ProjectN/am-kernels), branch: master

1. [NEMU](https://github.com/OpenXiangShan/NEMU), branch: master

## Getting Started

First, download all the dependency repositories and set the environment variables (refer to env.sh).

To generate Verilog:

```
make
```

To build and run the binary for emulation and difftest:

```
make emu
./build/emu -i path/to/risc-v/binary.bin
```
