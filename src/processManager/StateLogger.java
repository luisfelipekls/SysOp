package processManager;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

// Registra, EM ARQUIVO, cada mudança de estado dos processos durante o
// escalonamento, no formato pedido no enunciado:
//
//   ID | Nome do prog | Razao | Estado inicial | Proximo estado | Tabela de paginas [pag,frame]
//
// O log é persistente (modo append): os registros ficam guardados para serem
// consultados após a finalização do 'execAll'. O usuário pode esvaziá-lo
// voluntariamente pelo comando 'clearLog' (método clear()).
//
// O acesso é sincronizado porque as transições vêm de várias threads
// (Shell, Escalonador, CPU e Console). Nada é impresso no console — a
// informação fica somente no arquivo.
public class StateLogger {
    private final String fileName;
    private PrintWriter file;
    private final Object lock = new Object();

    private static final String FMT = "%-4s %-15s %-13s %-13s %-13s %s%n";
    private static final String SEP =
            "---- --------------- ------------- ------------- ------------- ------------------------------";

    public StateLogger(String fileName) {
        this.fileName = fileName;
        open();
    }

    // Abre o arquivo em modo append, preservando registros de execuções anteriores.
    // Só escreve o cabeçalho se o arquivo ainda não existir ou estiver vazio.
    private void open() {
        try {
            File f = new File(fileName);
            boolean novo = !f.exists() || f.length() == 0;
            file = new PrintWriter(new FileWriter(f, true), true); // append + autoflush
            if (novo) writeHeader();
        } catch (IOException e) {
            System.out.println("[LOG] Nao foi possivel abrir '" + fileName + "': " + e.getMessage());
            file = null;
        }
    }

    private void writeHeader() {
        if (file == null) return;
        file.printf(FMT, "ID", "Nome do prog", "Razao",
                "Estado ini", "Prox estado", "Tabela de paginas [pag,frame]");
        file.println(SEP);
    }

    // Registra uma transição de estado de um processo (somente no arquivo).
    public void log(PCB pcb, String reason, String fromState, String toState) {
        synchronized (lock) {
            if (file == null) return;
            file.printf(FMT, pcb.id, pcb.getProgramName(), reason, fromState, toState, pageTable(pcb));
        }
    }

    // Esvazia o arquivo de log voluntariamente (sobrescreve e reescreve o cabeçalho).
    public void clear() {
        synchronized (lock) {
            if (file != null) file.close();
            try {
                file = new PrintWriter(new FileWriter(fileName, false), true); // sobrescreve
                writeHeader();
                System.out.println("[LOG] Arquivo '" + fileName + "' limpo.");
            } catch (IOException e) {
                System.out.println("[LOG] Nao foi possivel limpar '" + fileName + "': " + e.getMessage());
                file = null;
            }
        }
    }

    // Monta a lista de triplas [pagina, frame] a partir da tabela de paginas do PCB.
    private String pageTable(PCB pcb) {
        if (pcb.pages == null) return "{}";
        StringBuilder sb = new StringBuilder("{ ");
        for (int i = 0; i < pcb.pages.length; i++) {
            sb.append("[").append(i).append(",").append(pcb.pages[i].frame.index).append("]");
            if (i < pcb.pages.length - 1) sb.append(", ");
        }
        sb.append(" }");
        return sb.toString();
    }

    public void close() {
        synchronized (lock) {
            if (file != null) {
                file.close();
                file = null;
            }
        }
    }
}
