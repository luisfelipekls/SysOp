package processManager;

import memoria.Frame;
import memoria.Page;

public class PCB {
    private static int idCounter = 0;
    public Integer id;
    public ProcessStatus status;
    public Frame[] frames;
    public Page[] pages;
    public Integer processPc;
    public int[] savedRegisters = new int[10]; // contexto dos registradores para context switch
    private String programName;

    PCB(){
        id = idCounter++;
        status = ProcessStatus.CREATED;
        programName = "unknown";
    }

    public void setFrames(Frame[] programFrames){
        frames = programFrames;
        processPc = 0; // PC começa em 0 (endereço lógico)
    }

    public void setPages(Page[] programPages) {
        pages = programPages;
    }

    public void setProgramName(String name) {
        this.programName = name;
    }

    public String getProgramName() {
        return programName;
    }
}
