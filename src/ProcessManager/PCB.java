package ProcessManager;

import memoria.Frame;
import memoria.Page;

import java.util.UUID;

public class PCB {
    private static int idCounter = 0;
    public Integer id;
    public ProcessStatus status;
    public Frame[] frames;
    public Page[] pages;
    public Integer processPc;
    private String programName;

    PCB(){
        id = idCounter++;
        status = ProcessStatus.CREATED;
        programName = "unknown";
    }

    public void setFrames(Frame[] programFrames){
        frames = programFrames;
        processPc = frames[0].start;
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
