package processManager;

import memoria.*;
import system.Sistema.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

// Gerência de Processos + coordenação das threads CPU e Escalonador.
//
// Modelo de TRÊS estados (running, ready, blocked) com execução concorrente:
//   - Thread Escalonador (schedulerLoop): escolhe o próximo pronto e o entrega à CPU.
//   - Thread CPU (cpuLoop): executa uma fatia de tempo e devolve o processo ao destino.
//   - Thread Console (io.GerenciadorIO): conclui o IO e devolve o processo aos prontos.
//   - Thread Shell (Main): cria processos (apenas aloca/carrega; só executam após 'execAll').
//
// O comando 'new' apenas cria o processo (estado CREATED). O escalonador só passa
// a despachar processos quando o 'execAll' habilita o escalonamento; ao esvaziar
// o sistema (nada pronto, executando ou bloqueado) o escalonamento é desligado e
// novos 'new' voltam a apenas alocar, sem rodar sozinhos.
//
// Toda a coordenação usa o monitor 'lock' (wait/notifyAll) + o semáforo 'semaCPU'.
public class ProcessManager {
    private final MemoryManager memoryManager;
    private final Memory memory;
    private final CPU cpu;
    private final Programs programs;

    // ----- estado compartilhado do "kernel" (protegido por 'lock') -----
    public final Object lock = new Object();
    public List<PCB> pcbReadyList = new ArrayList<>();   // fila de prontos
    public List<PCB> allProcesses = new ArrayList<>();   // todos os processos vivos (para o ps)
    public PCB running;                                  // processo atualmente na CPU
    private boolean cpuIdle = true;                      // true quando a CPU está livre para receber um processo
    private boolean schedulingEnabled = false;           // só despacha processos depois do 'execAll'
    private int blockedCount = 0;                        // nº de processos bloqueados em IO

    // Semáforo que libera a Thread CPU para executar uma fatia (semaCPU do enunciado).
    private final Semaphore semaCPU = new Semaphore(0);

    // Registra cada mudança de estado dos processos (somente em arquivo, persistente).
    private final StateLogger logger = new StateLogger("escalonamento.log");

    public ProcessManager(Memory memory, MemoryManager memoryManager, CPU cpu, Programs programs) {
        this.memory = memory;
        this.memoryManager = memoryManager;
        this.cpu = cpu;
        this.programs = programs;
    }

    public void closeLog() {
        logger.close();
    }

    // Limpa o arquivo de log de escalonamento a pedido do usuário (comando 'clearLog').
    public void clearLog() {
        logger.clear();
    }

    // ----------------------------------------------------------------------
    // Criação de processos
    // ----------------------------------------------------------------------
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
        System.out.println("[PM] Processo criado: PID=" + currentProgramPCB.id + ", Programa=" + programName);
        register(currentProgramPCB);   // apenas registra (CREATED); não executa até o 'execAll'
        return true;
    }

    // Registra um processo recém-criado: fica em CREATED, fora da fila de prontos.
    // NÃO acorda o escalonador — o 'new' apenas aloca/carrega, sem disparar execução.
    private void register(PCB pcb) {
        synchronized (lock) {
            pcb.status = ProcessStatus.CREATED;
            allProcesses.add(pcb);
            logger.log(pcb, "criacao", "nulo", "criado");
        }
    }

    // ----------------------------------------------------------------------
    // Carga de processos parametrizável: cria 'n' processos a serem escalonados.
    //
    // Para exemplificar a fila de bloqueados, a carga inclui:
    //   - 1 processo de ENTRADA (fibonacciREAD, faz READ) — fica ANTES do de saída,
    //     bloqueia cedo aguardando o teclado;
    //   - 1 processo de SAÍDA (fatorialV2, faz WRITE);
    //   - os demais sem IO (PB, progMinimo, fibonacci10, ...).
    // Ao final, lista os processos criados. A execução só começa com 'execAll'.
    // ----------------------------------------------------------------------
    public boolean createLoad(int n) {
        if (n <= 0) {
            System.out.println("[PM] Quantidade invalida: informe um numero >= 1.");
            return false;
        }

        // Monta o plano de programas respeitando a ordem entrada -> saida -> demais.
        String[] semIO = {"PB", "progMinimo", "fibonacci10", "fatorial", "fibonacci10v2", "PC"};
        List<String> plano = new ArrayList<>();
        plano.add("fibonacciREAD");                 // 1º: entrada (READ) — bloqueia primeiro
        if (n >= 2) plano.add("fatorialV2");        // 2º: saida (WRITE)
        for (int i = 0; plano.size() < n; i++) {    // demais: sem IO
            plano.add(semIO[i % semIO.length]);
        }

        System.out.println("\n[PM] Iniciando carga de " + n + " processo(s)...");
        int criados = 0;
        for (int i = 0; i < n; i++) {
            if (createProcess(plano.get(i))) criados++;
        }
        System.out.println("[PM] Carga concluida: " + criados + " de " + n + " processo(s) criado(s).");
        listProcesses();
        System.out.println("Use 'execAll' para escalonar e executar a carga.");
        return criados > 0;
    }

    // ----------------------------------------------------------------------
    // execAll: admite todos os processos criados na fila de prontos e habilita
    // o escalonamento, entrando no loop de Round Robin. O escalonamento é
    // desligado automaticamente quando o sistema esvazia (ver cpuLoop).
    // ----------------------------------------------------------------------
    public void execAll() {
        synchronized (lock) {
            boolean any = false;
            for (PCB pcb : allProcesses) {
                if (pcb.status == ProcessStatus.CREATED) {
                    pcb.status = ProcessStatus.READY;
                    pcbReadyList.add(pcb);
                    logger.log(pcb, "admissao", "criado", "pronto");
                    any = true;
                }
            }
            if (!any && pcbReadyList.isEmpty() && running == null && blockedCount == 0) {
                System.out.println("[PM] Nenhum processo para escalonar. Crie processos com 'new'.");
                return;
            }
            schedulingEnabled = true;       // libera o escalonador
            lock.notifyAll();               // acorda a Thread Escalonador
        }
    }

    // ----------------------------------------------------------------------
    // Thread Escalonador: escolhe o próximo pronto quando a CPU está livre
    // ----------------------------------------------------------------------
    public void schedulerLoop() {
        while (true) {
            PCB next;
            synchronized (lock) {
                // espera enquanto o escalonamento não estiver habilitado ('execAll')
                // ou não houver o que escalonar (CPU ocupada ou fila vazia)
                while (!schedulingEnabled || !cpuIdle || pcbReadyList.isEmpty()) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                next = pcbReadyList.remove(0);      // Round Robin: pega o primeiro da fila
                running = next;
                next.status = ProcessStatus.EXECUTING;
                cpuIdle = false;                    // CPU passa a ocupada
                logger.log(next, "escalona", "pronto", "rodando");
            }
            semaCPU.release();                      // libera a Thread CPU para rodar a fatia
        }
    }

    // ----------------------------------------------------------------------
    // Thread CPU: executa uma fatia e decide o destino do processo
    // ----------------------------------------------------------------------
    public void cpuLoop() {
        while (true) {
            try {
                semaCPU.acquire();                 // aguarda o escalonador configurar um processo
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            PCB p = running;
            CPUStatus status = cpu.runQuantum(p);  // executa a fatia de tempo

            synchronized (lock) {
                switch (status) {
                    case FINISHED:                 // Rot Trat STOP/erro: finaliza o processo
                        // NÃO desaloca a memória: o processo permanece em allProcesses
                        // no estado FINISHED, mantendo seus frames carregados para que o
                        // usuário possa inspecioná-los com 'dumpP <pid>' após o execAll.
                        // Use 'rm <pid>' para liberar a memória voluntariamente.
                        logger.log(p, "stop", "rodando", "finalizado");
                        p.status = ProcessStatus.FINISHED;
                        break;
                    case BLOCKED:                  // pediu IO: já foi para a fila de bloqueados (syscall)
                        logger.log(p, "solicita IO", "rodando", "bloqueado");
                        p.status = ProcessStatus.BLOCKED;
                        blockedCount++;
                        break;
                    case PREEMPTED:                // Rot Trat TIMER: volta ao fim da fila de prontos
                        logger.log(p, "fatia tempo", "rodando", "pronto");
                        p.status = ProcessStatus.READY;
                        pcbReadyList.add(p);
                        break;
                }
                running = null;
                cpuIdle = true;                    // CPU livre novamente

                // Sistema esvaziou (nada pronto, executando ou bloqueado): desliga o
                // escalonamento para que novos 'new' voltem a apenas alocar, sem rodar.
                if (pcbReadyList.isEmpty() && blockedCount == 0) {
                    schedulingEnabled = false;
                    System.out.println("[PM] Fila de prontos vazia. Escalonamento encerrado (use 'execAll' para retomar).");
                }
                lock.notifyAll();                  // acorda o escalonador para a próxima fatia
            }
        }
    }

    // ----------------------------------------------------------------------
    // Rot Trat Ret IO: chamada pela Thread Console quando o IO conclui.
    // Devolve o processo desbloqueado à fila de prontos e acorda o escalonador.
    // ----------------------------------------------------------------------
    public void unblockProcess(PCB process) {
        synchronized (lock) {
            if (blockedCount > 0) blockedCount--;
            process.status = ProcessStatus.READY;
            pcbReadyList.add(process);
            logger.log(process, "retorno IO", "bloqueado", "pronto");
            lock.notifyAll();
        }
    }

    // ----------------------------------------------------------------------
    // Remoção de processos
    // ----------------------------------------------------------------------
    public void dealocateProcess(int id) {
        synchronized (lock) {
            PCB pcb = allProcesses.stream().filter(a -> a.id == id).findFirst().orElse(null);
            if (pcb == null) {
                System.out.println("[PM] Erro: Processo PID=" + id + " não encontrado.");
                return;
            }
            if (pcb == running) {
                System.out.println("[PM] Processo PID=" + id + " está em execução; não pode ser removido agora.");
                return;
            }
            if (pcb.status == ProcessStatus.BLOCKED) {
                System.out.println("[PM] Processo PID=" + id + " está bloqueado em IO; não pode ser removido agora.");
                return;
            }
            pcbReadyList.remove(pcb);
            allProcesses.remove(pcb);
            memoryManager.deallocate(pcb.pages);
            System.out.println("[PM] Processo PID=" + id + " removido.");
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
        return allProcesses;
    }

    public PCB getProcess(int pid) {
        synchronized (lock) {
            return allProcesses.stream().filter(p -> p.id == pid).findFirst().orElse(null);
        }
    }

    // Dump do espaço de endereçamento de UM processo, em ordem lógica, mostrando a
    // tradução endereço lógico -> físico. Útil para conferir, após o execAll, que um
    // valor lido/escrito (ex.: posição lógica 55) foi salvo corretamente na memória.
    public void dumpProcess(int pid) {
        PCB pcb;
        synchronized (lock) {
            pcb = allProcesses.stream().filter(p -> p.id == pid).findFirst().orElse(null);
        }
        if (pcb == null) {
            System.out.println("[PM] Processo PID=" + pid + " nao encontrado.");
            return;
        }

        int pageSize = pcb.frames[0].end - pcb.frames[0].start + 1;
        int total = pcb.pages.length * pageSize;

        System.out.println("\n=== DUMP DO PROCESSO PID=" + pcb.id + " (" + pcb.getProgramName() + ") ===");
        System.out.println("Status: " + pcb.status);
        StringBuilder pt = new StringBuilder("Tabela de paginas [pag,frame]: { ");
        for (int i = 0; i < pcb.pages.length; i++) {
            pt.append("[").append(i).append(",").append(pcb.pages[i].frame.index).append("]");
            if (i < pcb.pages.length - 1) pt.append(", ");
        }
        System.out.println(pt.append(" }"));
        System.out.printf("%-9s %-9s %s%n", "log", "fis", "conteudo [ opc, ra, rb, p ]");
        System.out.println("--------- --------- ---------------------------------");
        for (int log = 0; log < total; log++) {
            int phys = pcb.pages[log / pageSize].frame.start + (log % pageSize);
            Word w = memory.pos[phys];
            System.out.printf("%-9d %-9d [ %s, %d, %d, %d ]%n", log, phys, w.opc, w.ra, w.rb, w.p);
        }
        System.out.println();
    }

    public void listProcesses() {
        System.out.println("\n--- Lista de Processos ---");
        synchronized (lock) {
            if (allProcesses.isEmpty()) {
                System.out.println("Nenhum processo no sistema.");
            } else {
                System.out.printf("%-6s %-15s %-12s%n", "PID", "Programa", "Status");
                System.out.println("------ --------------- ------------");
                for (PCB pcb : allProcesses) {
                    System.out.printf("%-6d %-15s %-12s%n",
                        pcb.id,
                        pcb.getProgramName(),
                        pcb.status);
                }
            }
        }
        System.out.println();
    }
}
