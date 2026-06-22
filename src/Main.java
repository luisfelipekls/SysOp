import io.GerenciadorIO;
import memoria.MemoryManager;
import processManager.ProcessManager;
import system.Sistema;

import java.util.Scanner;

public class Main {
    private static Sistema sistema;
    private static MemoryManager memoryManager;
    private static ProcessManager processManager;
    private static GerenciadorIO gerenciadorIO;
    private static Scanner scanner;
    private static boolean traceEnabled = false;

    public static void main(String[] args) {
        int memorySize = 1024;
        int pageSize = 16;

        sistema = new Sistema(memorySize);
        memoryManager = new MemoryManager(sistema.hw.mem, pageSize);
        processManager = new ProcessManager(
            sistema.hw.mem,
            memoryManager,
            sistema.hw.cpu,
            sistema.progs
        );

        sistema.setProcessManager(processManager);
        sistema.setMemoryManager(memoryManager);

        sistema.hw.cpu.setAddressOfHandlers(sistema.so.ih, sistema.so.sc);
        sistema.hw.cpu.setUtilities(sistema.so.utils);
        sistema.hw.cpu.setPageSize(pageSize);
        sistema.hw.cpu.setDebug(false); // execução concorrente: trace desligado por padrão (use traceOn)

        // Thread Console (Gerenciador de IO): roda em paralelo consumindo a fila de
        // requisições de IO, lendo/escrevendo dados e devolvendo os processos à fila
        // de prontos quando o IO termina.
        gerenciadorIO = new GerenciadorIO(sistema.hw.mem, processManager);
        sistema.setGerenciadorIO(gerenciadorIO);

        Thread consoleThread = new Thread(gerenciadorIO, "Thread-Console");
        consoleThread.setDaemon(true);
        consoleThread.start();

        // Thread Escalonador: escolhe o próximo processo pronto quando a CPU está livre.
        Thread schedulerThread = new Thread(processManager::schedulerLoop, "Thread-Escalonador");
        schedulerThread.setDaemon(true);
        schedulerThread.start();

        // Thread CPU: executa uma fatia de tempo do processo escalonado e o devolve
        // ao seu destino (pronto / bloqueado / finalizado).
        Thread cpuThread = new Thread(processManager::cpuLoop, "Thread-CPU");
        cpuThread.setDaemon(true);
        cpuThread.start();

        scanner = new Scanner(System.in);
        System.out.println("=== Sistema Operacional Simulado ===");
        System.out.println("Comandos disponíveis:");
        System.out.println("  new <program>  - Cria um novo processo");
        System.out.println("  load <n>       - Cria uma carga de n processos (com IO) a escalonar");
        System.out.println("  rm <pid>       - Remove um processo");
        System.out.println("  ps             - Lista todos os processos");
        System.out.println("  dump           - Mostra estado da memória");
        System.out.println("  dumpM          - Mostra estado do gerenciador de memória");
        System.out.println("  dumpP <pid>    - Dump da memória de um processo (ordem lógica)");
        System.out.println("  exec <pid>     - Executa um processo");
        System.out.println("  execAll        - Executa todos os processos");
        System.out.println("  traceOn        - Habilita rastreamento (debug)");
        System.out.println("  traceOff       - Desabilita rastreamento (debug)");
        System.out.println("  clearLog       - Limpa o arquivo de log de escalonamento");
        System.out.println("  exit           - Sai do sistema");
        System.out.println();
        System.out.println("O log de transicoes de estado e gravado em 'escalonamento.log'.");
        System.out.println();
        System.out.println("Programas disponíveis:");
        listAvailablePrograms();
        System.out.println();

        commandLoop();

        scanner.close();
        processManager.closeLog();
        System.out.println("Sistema encerrado. Log de transicoes em 'escalonamento.log'.");
    }

    private static void commandLoop() {
        while (true) {
            System.out.print("> ");
            String line = scanner.nextLine();

            // Se há uma leitura de IO pendente, a linha digitada é dado do programa
            // (entregue à Thread Console), e não um comando do shell.
            if (gerenciadorIO.hasPendingRead()) {
                gerenciadorIO.deliverInput(line);
                continue;
            }

            String input = line.trim();
            if (input.isEmpty()) continue;

            String[] parts = input.split("\\s+");
            String command = parts[0].toLowerCase();

            switch (command) {
                case "new":
                    handleNew(parts);
                    break;
                case "load":
                    handleLoad(parts);
                    break;
                case "rm":
                    handleRm(parts);
                    break;
                case "ps":
                    handlePs();
                    break;
                case "dump":
                    handleDump();
                    break;
                case "dumpm":
                    handleDumpM();
                    break;
                case "dumpp":
                    handleDumpP(parts);
                    break;
                case "exec":
                    handleExec(parts);
                    break;
                case "execall":
                    handleExecAll();
                    break;
                case "traceon":
                    handleTraceOn();
                    break;
                case "traceoff":
                    handleTraceOff();
                    break;
                case "clearlog":
                    processManager.clearLog();
                    break;
                case "exit":
                    return;
                case "help":
                    showHelp();
                    break;
                default:
                    System.out.println("Comando desconhecido: " + command);
                    System.out.println("Digite 'help' para ver os comandos disponíveis.");
            }
        }
    }

    private static void handleNew(String[] parts) {
        if (parts.length < 2) {
            System.out.println("Uso: new <program>");
            return;
        }

        String programName = parts[1];
        boolean success = processManager.createProcess(programName);

        if (!success) {
            System.out.println("Falha ao criar processo. Verifique se o programa existe e se há memória disponível.");
        }
    }

    private static void handleLoad(String[] parts) {
        if (parts.length < 2) {
            System.out.println("Uso: load <n>  (cria uma carga de n processos a escalonar)");
            return;
        }
        try {
            int n = Integer.parseInt(parts[1]);
            processManager.createLoad(n);
        } catch (NumberFormatException e) {
            System.out.println("Quantidade invalida: " + parts[1]);
        }
    }

    private static void handleRm(String[] parts) {
        if (parts.length < 2) {
            System.out.println("Uso: rm <pid>");
            return;
        }

        try {
            int pid = Integer.parseInt(parts[1]);
            processManager.dealocateProcess(pid);
            System.out.println("Processo PID=" + pid + " removido.");
        } catch (NumberFormatException e) {
            System.out.println("PID inválido: " + parts[1]);
        } catch (Exception e) {
            System.out.println("Erro ao remover processo: " + e.getMessage());
        }
    }

    private static void handlePs() {
        processManager.listProcesses();
    }

    private static void handleDump() {
        System.out.println("\n=== DUMP DA MEMÓRIA ===");
        sistema.so.utils.dump(0, sistema.hw.mem.pos.length);
    }

    private static void handleDumpM() {
        System.out.println("\n=== DUMP DO GERENCIADOR DE MEMÓRIA ===");
        memoria.Frame[] availableFrames = memoryManager.getAvailableFrames();
        System.out.println("Frames disponíveis: " + availableFrames.length);
        System.out.println("Frames ocupados: " + (sistema.hw.mem.pos.length / 16 - availableFrames.length));
        System.out.println();
    }

    private static void handleDumpP(String[] parts) {
        if (parts.length < 2) {
            System.out.println("Uso: dumpP <pid>  (dump da memoria de um processo, em ordem logica)");
            return;
        }
        try {
            int pid = Integer.parseInt(parts[1]);
            processManager.dumpProcess(pid);
        } catch (NumberFormatException e) {
            System.out.println("PID invalido: " + parts[1]);
        }
    }

    private static void handleExec(String[] parts) {
        // O 'new' apenas cria/aloca o processo. A execução só começa com 'execAll',
        // que coloca os processos criados na fila de prontos e inicia o escalonamento.
        System.out.println("[Shell] Use 'execAll' para escalonar e executar os processos criados com 'new'.");
    }

    private static void handleExecAll() {
        processManager.execAll();
    }

    private static void handleTraceOn() {
        traceEnabled = true;
        sistema.hw.cpu.setDebug(true);
        System.out.println("Rastreamento habilitado.");
    }

    private static void handleTraceOff() {
        traceEnabled = false;
        sistema.hw.cpu.setDebug(false);
        System.out.println("Rastreamento desabilitado.");
    }

    private static void showHelp() {
        System.out.println("\n=== COMANDOS DISPONÍVEIS ===");
        System.out.println("  new <program>  - Cria um novo processo");
        System.out.println("  load <n>       - Cria uma carga de n processos (com IO) a escalonar");
        System.out.println("  rm <pid>       - Remove um processo");
        System.out.println("  ps             - Lista todos os processos");
        System.out.println("  dump           - Mostra estado da memória");
        System.out.println("  dumpM          - Mostra estado do gerenciador de memória");
        System.out.println("  dumpP <pid>    - Dump da memória de um processo (ordem lógica)");
        System.out.println("  exec <pid>     - Executa um processo");
        System.out.println("  traceOn        - Habilita rastreamento (debug)");
        System.out.println("  traceOff       - Desabilita rastreamento (debug)");
        System.out.println("  clearLog       - Limpa o arquivo de log de escalonamento");
        System.out.println("  exit           - Sai do sistema");
        System.out.println();
    }

    private static void listAvailablePrograms() {
        System.out.println("Programas disponíveis:");
        String[] programNames = {
            "fatorial", "fatorialV2", "progMinimo",
            "fibonacci10", "fibonacci10v2", "fibonacciREAD",
            "PB", "PC"
        };
        for (String name : programNames) {
            System.out.println("  - " + name);
        }
    }
}