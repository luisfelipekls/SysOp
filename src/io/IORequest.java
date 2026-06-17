package io;

import processManager.PCB;

// Uma requisição de IO enfileirada para a Thread Console.
// Carrega o processo que pediu o IO, o tipo da operação e o endereço
// (lógico para mensagens / físico já traduzido para acessar a memória).
public class IORequest {
    public enum Type { READ, WRITE }

    public final PCB process;
    public final Type type;
    public final int logicalAddress;
    public final int physicalAddress;

    public IORequest(PCB process, Type type, int logicalAddress, int physicalAddress) {
        this.process = process;
        this.type = type;
        this.logicalAddress = logicalAddress;
        this.physicalAddress = physicalAddress;
    }
}