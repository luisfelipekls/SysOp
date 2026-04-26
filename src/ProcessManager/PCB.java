package ProcessManager;

import memoria.Frame;
import memoria.Page;

import java.util.UUID;

public class PCB {
    private static int idCounter = 0;
    public Integer id;
    private ProcessStatus status;
    public Frame[] frames;
    public Page[] pages;

    PCB(){
        id = idCounter++;
        status = ProcessStatus.CREATED;
    }

    public void setFrames(Frame[] programFrames){
        frames = programFrames;
    }

    public void setPages(Page[] programPages) {
        pages = programPages;
    }
}
