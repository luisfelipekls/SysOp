package memoria;

import system.Sistema.*;

public class Frame {
    public static int indexCounter = 0;
    public int index;
    public int start;
    public int end;
    public boolean isOccupied;

    public Frame(int start, int end) {
        this.start = start;
        this.end = end;
        this.index = indexCounter;
        indexCounter++;
    }
}
