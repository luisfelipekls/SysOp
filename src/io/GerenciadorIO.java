package io;

import processManager.PCB;
import processManager.ProcessManager;
import system.Sistema.Memory;
import system.Sistema.Opcode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.atomic.AtomicInteger;

// Thread Console + Gerenciador de IO (estágio "Multithreaded + IO").
// Roda em sua própria thread consumindo uma fila de requisições de IO:
//   - READ : lê um inteiro do terminal e persiste na memória do processo
//   - WRITE: escreve na tela um valor previamente persistido na memória
// Ao terminar cada IO, a rotina de retorno desbloqueia o processo e o
// devolve à fila de prontos.
public class GerenciadorIO implements Runnable {
    public List<PCB> blockedProcesses = new ArrayList<>();

    private final BlockingQueue<IORequest> requestQueue = new LinkedBlockingQueue<>();
    // Canal de entrada: o shell (único dono do teclado) entrega aqui a linha
    // digitada quando há uma leitura pendente; a Console aguarda nele.
    private final SynchronousQueue<String> inputChannel = new SynchronousQueue<>();
    private final AtomicInteger pendingReads = new AtomicInteger(0);

    private final Memory memory;
    private final ProcessManager processManager;

    public GerenciadorIO(Memory memory, ProcessManager processManager) {
        this.memory = memory;
        this.processManager = processManager;
    }

    public synchronized void addBlockedProcess(PCB process) {
        blockedProcesses.add(process);
    }

    public synchronized void removeBlockedProcess(PCB process) {
        blockedProcesses.remove(process);
    }

    // Enfileira uma requisição de IO (chamado pela rotina de syscall, na thread da CPU).
    public void submit(IORequest request) {
        if (request.type == IORequest.Type.READ) {
            pendingReads.incrementAndGet();
        }
        requestQueue.add(request);
    }

    // O shell usa isto para saber se a próxima linha digitada é dado de leitura
    // (e não um comando).
    public boolean hasPendingRead() {
        return pendingReads.get() > 0;
    }

    // O shell entrega a linha digitada para a Thread Console que aguarda leitura.
    public void deliverInput(String line) {
        try {
            inputChannel.put(line); // bloqueia até a Console consumir
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Thread Console: consome requisições de IO indefinidamente.
    @Override
    public void run() {
        while (true) {
            try {
                IORequest request = requestQueue.take(); // bloqueia até haver pedido
                handle(request);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void handle(IORequest request) throws InterruptedException {
        switch (request.type) {
            case READ:
                System.out.print("\n[Console] IN  (PID=" + request.process.id
                        + ", endereço lógico " + request.logicalAddress + "): ");
                int value = readInt();
                // devolve o dado lido persistindo-o na memória do processo
                memory.pos[request.physicalAddress].opc = Opcode.DATA;
                memory.pos[request.physicalAddress].p = value;
                break;
            case WRITE:
                int out = memory.pos[request.physicalAddress].p;
                System.out.println("\n[Console] OUT (PID=" + request.process.id + "): " + out);
                break;
        }
        ioReturn(request.process);
    }

    private int readInt() throws InterruptedException {
        while (true) {
            String line = inputChannel.take(); // aguarda o shell entregar a linha
            try {
                int value = Integer.parseInt(line.trim());
                pendingReads.decrementAndGet();
                return value;
            } catch (NumberFormatException e) {
                // mantém pendingReads > 0 para o shell continuar roteando linhas
                System.out.print("[Console] valor inválido, digite um inteiro: ");
            }
        }
    }

    // Rotina de retorno de IO: desbloqueia o processo e o devolve à fila de prontos.
    private void ioReturn(PCB process) {
        removeBlockedProcess(process);
        processManager.unblockProcess(process);
        System.out.println("[Console] IO concluído: PID=" + process.id
                + " desbloqueado e devolvido à fila de prontos.");
    }
}