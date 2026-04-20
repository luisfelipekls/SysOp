package memoria;

import system.Sistema.*;

public class Page {
    public Frame frame;
    Word[] words;

    public Page(Frame frame, int size) {
        this.frame = frame;
        this.words = new Word[size];
    }
}
