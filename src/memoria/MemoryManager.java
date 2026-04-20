package memoria;

import java.util.Arrays;

import system.Sistema.*;

public class MemoryManager {
    private int pageSize;

    private Memory memory;
    private Frame[] frameControl;

    public MemoryManager(Memory memory, int pageSize) {
        this.memory = memory;
        this.pageSize = pageSize;
        this.frameControl = new Frame[memory.pos.length / pageSize];

        initializeFrameControl();
    }

    public Frame[] getAvailableFrames() {
        return Arrays.stream(frameControl)
                .filter(frame -> !frame.isOccupied)
                .toArray(Frame[]::new);
    }

    private void initializeFrameControl() {
        for (int i = 0; i < frameControl.length; i++) {
            frameControl[i] = new Frame(i*pageSize, ((i+1)*pageSize)-1);
        }
    }

    public Page[] allocate(int wordsSize) {
        int numPages = wordsSize / pageSize;

        if (numPages > getAvailableFrames().length ) {
            return null;
        }

        Page[] pages = new Page[numPages];

        for (int i = 0; i < numPages; i++) {
            for(int j = 0; j < frameControl.length; j++) {
                if (memory.pos[j*pageSize] == null) {
                    pages[i] = new Page(frameControl[j], pageSize);
                }
            }
        }

        return pages;
    }

    public void deallocate(Page[] pages) {
        for (Page page : pages) {
            page.words = null;
            page.frame.isOccupied = false;

            for(int i = page.frame.start; i <= page.frame.end; i++) {
                memory.pos[i] = null;
            }
        }
    }
}
