import memoria.MemoryManager;
import ProcessManager.ProcessManager;
import system.Sistema;

import java.util.Scanner;

public class Main {
    private static Sistema sistema;
    private static MemoryManager memoryManager;
    private static ProcessManager processManager;
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

        scanner = new Scanner(System.in);
        System.out.println("=== Sistema Operacional Simulado ===");
        System.out.println("Comandos disponíveis:");
        System.out.println("  new <program>  - Cria um novo processo");
        System.out.println("  rm <pid>       - Remove um processo");
        System.out.println("  ps             - Lista todos os processos");
        System.out.println("  dump           - Mostra estado da memória");
        System.out.println("  dumpM          - Mostra estado do gerenciador de memória");
        System.out.println("  exec <pid>     - Executa um processo");
        System.out.println("  execAll        - Executa todos os processos");
        System.out.println("  traceOn        - Habilita rastreamento (debug)");
        System.out.println("  traceOff       - Desabilita rastreamento (debug)");
        System.out.println("  exit           - Sai do sistema");
        System.out.println();
        System.out.println("Programas disponíveis:");
        listAvailablePrograms();
        System.out.println();

        commandLoop();

        scanner.close();
        System.out.println("Sistema encerrado.");
    }

    private static void commandLoop() {
        while (true) {
            System.out.print("> ");
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) continue;

            String[] parts = input.split("\\s+");
            String command = parts[0].toLowerCase();

            switch (command) {
                case "new":
                    handleNew(parts);
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

    private static void handleExec(String[] parts) {
        if (parts.length < 2) {
            System.out.println("Uso: exec <pid>");
            return;
        }

        try {
            int pid = Integer.parseInt(parts[1]);
            processManager.executeProcess(pid);
        } catch (NumberFormatException e) {
            System.out.println("PID inválido: " + parts[1]);
        }
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
        System.out.println("  rm <pid>       - Remove um processo");
        System.out.println("  ps             - Lista todos os processos");
        System.out.println("  dump           - Mostra estado da memória");
        System.out.println("  dumpM          - Mostra estado do gerenciador de memória");
        System.out.println("  exec <pid>     - Executa um processo");
        System.out.println("  traceOn        - Habilita rastreamento (debug)");
        System.out.println("  traceOff       - Desabilita rastreamento (debug)");
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