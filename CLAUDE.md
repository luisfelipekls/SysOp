# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**SysOp** is an educational operating system simulator for PUCRS (Prof. Fernando Dotti). It simulates core OS concepts: CPU instruction execution, memory management (physical paging), process management, and process scheduling.

## Assignment Structure

The work is split into incremental parts, each building on the previous:

- **T1-A — Memory Manager (GM)**: Physical paging — allocate/deallocate frames, load program into frames. ✅ Done (`memoria/MemoryManager.java`)
- **T1-B — T1-A + Process Manager (GP)**: PCB, ready queue, create/deallocate process, exec, CLI commands. ✅ Done (`processManager/`)
- **T1-C — T1-B + Scheduler (Escalonador)**: Add Round-Robin scheduling with quantum (timer interrupt). In progress.

## Build & Run

No build tools (Maven/Gradle). Compile and run manually:

```bash
# Compile all sources (from repo root)
javac -d out $(find src -name "*.java")

# Run the interactive CLI
java -cp out Main
```

CLI commands: `new <program>`, `rm <pid>`, `ps`, `dump`, `dumpM`, `exec <pid>`, `execAll`, `traceOn`/`traceOff`, `exit`.

Available programs: `fatorial`, `fatorialV2`, `progMinimo`, `fibonacci10`, `fibonacci10v2`, `fibonacciREAD`, `PB`, `PC`.

## Architecture

### Layers

**1. Hardware simulation (`system/Sistema.java`)**
- RAM: `Memory` with a `Word[]` array (1024 words by default, configurable)
- `Word` = one instruction: opcode + `ra`, `rb` registers + parameter `p`
- `CPU`: FETCH-EXECUTE cycle, 10 general-purpose registers, interrupt flags (`noInterrupt`, `intEnderecoInvalido`, `intInstrucaoInvalida`, `intOverflow`)
- Interrupt handler (`ih`) and syscall handler (`sysCall`) are injected via setters
- `cpu.run(pcb, isExecAll)` — executes with a cycle limit of 5 instructions for preemption

**2. Memory management (`memoria/` package)**
- `MemoryManager.java` — physical paging: `allocate(nWords)` returns `Page[]` (each `Page` holds a `Frame`); `deallocate(Page[])` frees frames
- `Frame.java` — represents a physical frame: `start`, `end`, `isOccupied`
- `Page.java` — logical page mapped to a physical `Frame`
- Frame index `f` spans physical addresses `[f * pageSize, (f+1) * pageSize - 1]`
- Default: tamMem=1024, tamPg=16 → 64 frames

**3. Process management (`processManager/` package)**
- `PCB.java` — auto-increment PID, `ProcessStatus` enum, `frames[]`, `pages[]`, `processPc` (saved PC for context switch)
- `ProcessStatus.java` — state enum: `CREATED → READY → EXECUTING → PAUSE → FINISHED`
- `processManager.java` — `createProcess(name)`, `dealocateProcess(id)`, `executeProcess(pid, isExecAll)`, `execAll()`, `listProcesses()`; holds `pcbReadyList` (ready queue) and `running` pointer

**4. CLI (`Main.java`)** — interactive loop wiring all layers together

### Key design notes

**Address translation**: Per the T1-A spec, programs use *logical* addresses (contiguous from 0). During execution, logical addresses must be translated to physical addresses using the process's page table. Currently `processPc` is set to `frames[0].start` (physical), and the CPU accesses `m[]` directly — address translation for data access (LDD/STD/LDX/STX) is not yet fully implemented.

**Programs are not modified**: Programs are written for the VM and always use logical (contiguous) addresses. The GM/GP are responsible for translating during load and execution.

**Scheduler (T1-C)**: The timer interrupt triggers after N instructions (currently 5) — this is Round Robin by instruction count. On timer interrupt, the running process is saved to the ready queue and the scheduler picks the next process. The architecture diagrams (`docs/SO-EsquematicoAula4Set (1).pdf`) show three evolution stages:
1. **Sequential** (current): single thread, `Main` drives everything
2. **Multithreaded**: `Thread Shell` + `Thread CPU` (waits on `semaCPU`) + `Thread Escalonador` (waits on `semaSch`); interrupt/timer routines signal semaphores
3. **Multithreaded + IO**: adds `Thread Console`, blocked queue (`Fila Bloqueados`), IO request queue, `Rot Trat Ret IO`

**Interrupt/timer flow** (sequential target for T1-C):
- CPU completes cycle → timer interrupt → `Rot Trat TIMER`: save process state (PC + regs into PCB), put process in ready queue, call scheduler
- Scheduler: pick next from ready queue, restore CPU context, continue
- STOP/overflow/invalid → finalize process → call scheduler

**`execAll` behavior**: Runs all ready processes in order, each up to the 5-instruction quantum, using the timer interrupt to preempt and rotate through the list.