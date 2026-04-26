package ProcessManager;

import memoria.*;
import system.Sistema;
import system.Sistema.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ProcessManager {
    private MemoryManager memoryManager;

    //coloquei a memoria aqui para seguir o processo, mas não sei em que momento o process manager deve receber a instancia
    private Memory memory;
    //criei como lista de pronto mas acho que posteriormente deve virar só uma lista mesmo
    private List<PCB> pcbReadyList = new ArrayList<>();
    private PCB running;

    public boolean createProcess(Program program){
        //pegando as páginas para alocação posterior
        Page[] programPages =  memoryManager.allocate(program.image.length);
        //isso daqui é para verificar se tinha memória mesmo
        if(programPages == null){
            return false;
        }
        //pegando os frames das páginas para poder manipular a memória posteriormente
        Frame[] frames = getFramesPromPages(programPages);
        PCB currentProgramPCB = new PCB();
        //setando os frames no PCB
        currentProgramPCB.setFrames(frames);
        //setando as páginas (só para ter a info depois para desalocar as páginas)
        currentProgramPCB.setPages(programPages);
        //carregando o programa na memória
        loadProgramInMemory(currentProgramPCB, program);
        //add na lista de pronto
        pcbReadyList.add(currentProgramPCB);
        return true;
    }

    public void dealocateProcess(int id){
        PCB currentPCB = pcbReadyList.stream().filter(a -> a.id == id).findFirst().get();
        memoryManager.deallocate(currentPCB.pages);
        pcbReadyList.remove(currentPCB);
        dealocateProcessInMemory(currentPCB);
    }

    private void dealocateProcessInMemory(PCB currentPCB) {
        //essa daqui é pra ver qual o tamanho do frame, o +1 é pq ia ficar com um a menos desse jeito
        int frameSize = currentPCB.frames[0].end - currentPCB.frames[0].start+1;
        for(int i = 0; i < currentPCB.frames.length; i++){
            //esse aqui é pra percorrer os frames e atribuir no endereço de memoria as palavras do programa
            for (int j = 0; j<frameSize; j++){
                memory.pos[currentPCB.frames[i].start+j] = null;
            }
        }
    }

    private void loadProgramInMemory(PCB currentProgramPCB, Program program) {
        //essa daqui é pra ver qual o tamanho do frame, o +1 é pq ia ficar com um a menos desse jeito
        int frameSize = currentProgramPCB.frames[0].end - currentProgramPCB.frames[0].start+1;
        //controlador para palavras do programa a serem postas na memória
        int countWords = 0;
        //percorre os frames do pcb
        for(int i = 0; i < currentProgramPCB.frames.length; i++){
            //esse aqui é pra percorrer os frames e atribuir no endereço de memoria as palavras do programa
            for (int j = 0; j<frameSize; j++){
                memory.pos[currentProgramPCB.frames[i].start+j] = program.image[countWords];
                countWords++;
            }
        }
    }

    private Frame[] getFramesPromPages(Page[] pages){
        Frame[] frames = new Frame[pages.length];
        for (int i=0; i< pages.length; i++){
            frames[i] = pages[i].frame;
        }
        return frames;
    }
}
