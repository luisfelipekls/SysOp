package ProcessManager;

import memoria.*;
import system.Sistema.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ProcessManager {
    private MemoryManager memoryManager;
    private Memory memory;
    private CPU cpu;
    private Sistema.Programs programs;
    private List<PCB> pcbReadyList = new ArrayList<>();
    private PCB running;

    public ProcessManager(Memory memory, MemoryManager memoryManager, CPU cpu, Sistema.Programs programs) {
        this.memory = memory;
        this.memoryManager = memoryManager;
        this.cpu = cpu;
        this.programs = programs;
    }

    public boolean createProcess(String programName) {
        Word[] programImage = programs.retrieveProgram(programName);
        if (programImage == null) {
            System.out.println("[PM] Erro: Programa '" + programName + "' não encontrado.");
            return false;
        }
        return createProcessFromImage(programImage, programName);
    }

    private boolean createProcessFromImage(Word[] programImage, String programName) {
        //pegando as páginas para alocação posterior
        Page[] programPages =  memoryManager.allocate(programImage.length);
        //isso daqui é para verificar se tinha memória mesmo
        if(programPages == null){
            return false;
        }
        //pegando os frames das páginas para poder manipular a memória posteriormente
        Frame[] frames = getFramesPromPages(programPages);
        PCB currentProgramPCB = new PCB();
        currentProgramPCB.setProgramName(programName);
        currentProgramPCB.setFrames(frames);
        currentProgramPCB.setPages(programPages);
        loadProgramInMemory(currentProgramPCB, programImage);
        pcbReadyList.add(currentProgramPCB);
        System.out.println("[PM] Processo criado: PID=" + currentProgramPCB.id + ", Programa=" + programName);
        return true;
    }

    public void dealocateProcess(int id){
        PCB currentPCB = pcbReadyList.stream().filter(a -> a.id == id).findFirst().get();
        memoryManager.deallocate(currentPCB.pages);
        pcbReadyList.remove(currentPCB);
        dealocateProcessInMemory(currentPCB);
    }

    private void dealocateProcessInMemory(PCB currentPCB) {
        //essa daqui é pra ver qual o tamanho do frame, o +1 é pq ia ficar com um a menos desse jeito
        int frameSize = currentPCB.frames[0].end - currentPCB.frames[0].start+1;
        for(int i = 0; i < currentPCB.frames.length; i++){
            //esse aqui é pra percorrer os frames e atribuir no endereço de memoria as palavras do programa
            for (int j = 0; j<frameSize; j++){
                memory.pos[currentPCB.frames[i].start+j] = null;
            }
        }
    }

    private void loadProgramInMemory(PCB currentProgramPCB, Word[] programImage) {
        int frameSize = currentProgramPCB.frames[0].end - currentProgramPCB.frames[0].start+1;
        int countWords = 0;
        for(int i = 0; i < currentProgramPCB.frames.length; i++){
            for (int j = 0; j<frameSize; j++){
                if (countWords < programImage.length) {
                    memory.pos[currentProgramPCB.frames[i].start+j] = programImage[countWords];
                    countWords++;
                }
            }
        }
    }

    private Frame[] getFramesPromPages(Page[] pages){
        Frame[] frames = new Frame[pages.length];
        for (int i=0; i< pages.length; i++){
            frames[i] = pages[i].frame;
        }
        return frames;
    }

    public List<PCB> getProcessList() {
        return pcbReadyList;
    }

    public PCB getProcess(int pid) {
        return pcbReadyList.stream().filter(p -> p.id == pid).findFirst().orElse(null);
    }

    public void executeProcess(int pid) {
        PCB pcb = getProcess(pid);
        if (pcb == null) {
            System.out.println("[PM] Erro: Processo PID=" + pid + " não encontrado.");
            return;
        }
        
        running = pcb;
        pcb.status = ProcessStatus.EXECUTING;
        
        int startAddress = pcb.frames[0].start;
        cpu.setContext(startAddress);
        
        System.out.println("[PM] Executando processo PID=" + pid + "...");
        cpu.run();
        
        pcb.status = ProcessStatus.FINISHED;
        running = null;
        System.out.println("[PM] Processo PID=" + pid + " finalizado.");
    }

    public void listProcesses() {
        System.out.println("\n--- Lista de Processos ---");
        if (pcbReadyList.isEmpty()) {
            System.out.println("Nenhum processo criado.");
        } else {
            System.out.printf("%-6s %-15s %-10s%n", "PID", "Programa", "Status");
            System.out.println("------ --------------- ----------");
            for (PCB pcb : pcbReadyList) {
                System.out.printf("%-6d %-15s %-10s%n", 
                    pcb.id, 
                    pcb.getProgramName(), 
                    pcb.status);
            }
        }
        System.out.println();
    }
}
