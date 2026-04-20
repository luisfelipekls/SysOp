package memoria;

import java.util.*;

/**
 * GerenciadorMemoriaVirtual - Gerenciador de Memória Virtual com Paginação
 *
 * Implementa um sistema de memória virtual baseado em paginação com:
 * - Tabela de páginas por processo
 * - Tabela de frames global
 * - Algoritmo de substituição Second Chance (Clock)
 * - Tradução de endereços virtuais para físicos
 * - Controle de bits de referência, modificação e validade
 * - Estatísticas de page faults, hits e substituições
 *
 * @see docs/DOCUMENTACAO_MEMORIA_VIRTUAL.md para documentação completa
 */
public class GerenciadorMemoriaVirtual {

    // ========================================================================================
    // ============================= ESTRUTURAS INTERNAS ======================================
    // ========================================================================================

    /**
     * Entrada da Tabela de Páginas (Page Table Entry - PTE).
     *
     * Cada processo possui uma tabela de páginas com N entradas,
     * onde N = ceil(tamanho do espaço de endereçamento virtual / tamanho da página).
     * Cada entrada mapeia uma página virtual a um frame físico.
     */
    public static class PageTableEntry {
        private int frameNumber;
        private boolean valid;
        private boolean dirty;
        private boolean referenced;

        public PageTableEntry() {
            this.frameNumber = -1;
            this.valid = false;
            this.dirty = false;
            this.referenced = false;
        }

        public int getFrameNumber()    { return frameNumber; }
        public boolean isValid()        { return valid; }
        public boolean isDirty()        { return dirty; }
        public boolean isReferenced()   { return referenced; }

        @Override
        public String toString() {
            return String.format("[frame=%2d, valid=%b, dirty=%b, ref=%b]",
                    frameNumber, valid, dirty, referenced);
        }
    }

    /**
     * Entrada da Tabela de Frames (Frame Table Entry - FTE).
     *
     * Cada frame da memória física possui uma entrada que indica
     * se está ocupado e, em caso positivo, por qual processo e página.
     */
    public static class FrameTableEntry {
        private int processId;
        private int pageNumber;
        private boolean occupied;

        public FrameTableEntry() {
            this.processId = -1;
            this.pageNumber = -1;
            this.occupied = false;
        }

        public int getProcessId()   { return processId; }
        public int getPageNumber()   { return pageNumber; }
        public boolean isOccupied()  { return occupied; }

        @Override
        public String toString() {
            if (!occupied) return "[livre]";
            return String.format("[pid=%d, página=%d]", processId, pageNumber);
        }
    }

    /**
     * Tabela de Páginas de um processo.
     * Encapsula o array de PageTableEntry e o ID do processo proprietário.
     */
    public static class PageTable {
        private final int processId;
        private final PageTableEntry[] entries;

        public PageTable(int processId, int numPages) {
            this.processId = processId;
            this.entries = new PageTableEntry[numPages];
            for (int i = 0; i < numPages; i++) {
                this.entries[i] = new PageTableEntry();
            }
        }

        public int getProcessId()           { return processId; }
        public int getNumPages()             { return entries.length; }
        public PageTableEntry get(int index) { return entries[index]; }
    }

    /**
     * Resultado de uma tradução de endereço virtual para físico.
     * Indica o endereço físico resultante e se houve page fault durante a tradução.
     */
    public static class TranslationResult {
        private final int physicalAddress;
        private final boolean pageFault;

        public TranslationResult(int physicalAddress, boolean pageFault) {
            this.physicalAddress = physicalAddress;
            this.pageFault = pageFault;
        }

        public int getPhysicalAddress() { return physicalAddress; }
        public boolean hadPageFault()    { return pageFault; }
    }

    // ========================================================================================
    // ============================= ATRIBUTOS ================================================
    // ========================================================================================

    private final int pageSize;
    private final int totalFrames;
    private final int physicalMemorySize;

    private final FrameTableEntry[] frameTable;
    private final Map<Integer, PageTable> pageTables;

    private int clockPointer;

    private int pageFaultCount;
    private int pageHitCount;
    private int replacementCount;

    // ========================================================================================
    // ============================= CONSTRUTOR ===============================================
    // ========================================================================================

    /**
     * Cria o gerenciador de memória virtual.
     *
     * @param physicalMemorySize tamanho total da memória física (em palavras/words)
     * @param pageSize           tamanho de cada página e frame (em palavras/words)
     * @throws IllegalArgumentException se os parâmetros forem inválidos
     */
    public GerenciadorMemoriaVirtual(int physicalMemorySize, int pageSize) {
        if (pageSize <= 0 || physicalMemorySize <= 0) {
            throw new IllegalArgumentException("Tamanhos devem ser positivos.");
        }
        if (physicalMemorySize % pageSize != 0) {
            throw new IllegalArgumentException(
                    "Tamanho da memória física (" + physicalMemorySize +
                    ") deve ser múltiplo do tamanho da página (" + pageSize + ").");
        }

        this.physicalMemorySize = physicalMemorySize;
        this.pageSize = pageSize;
        this.totalFrames = physicalMemorySize / pageSize;

        this.frameTable = new FrameTableEntry[totalFrames];
        for (int i = 0; i < totalFrames; i++) {
            frameTable[i] = new FrameTableEntry();
        }

        this.pageTables = new HashMap<>();
        this.clockPointer = 0;
        this.pageFaultCount = 0;
        this.pageHitCount = 0;
        this.replacementCount = 0;
    }

    // ========================================================================================
    // =================== GERENCIAMENTO DE PROCESSOS =========================================
    // ========================================================================================

    /**
     * Cria o espaço de endereçamento virtual para um novo processo.
     *
     * A tabela de páginas é criada com todas as entradas inválidas (demand paging):
     * os frames físicos só serão alocados quando o processo efetivamente acessar
     * cada página pela primeira vez (gerando um page fault controlado).
     *
     * @param processId  ID único do processo
     * @param memorySize tamanho da memória virtual necessária (em palavras)
     * @return true se a criação foi bem-sucedida
     */
    public boolean createProcess(int processId, int memorySize) {
        if (pageTables.containsKey(processId)) {
            System.out.println("[GM] Erro: Processo " + processId + " já existe.");
            return false;
        }
        if (memorySize <= 0) {
            System.out.println("[GM] Erro: Tamanho de memória deve ser positivo.");
            return false;
        }

        int numPages = (int) Math.ceil((double) memorySize / pageSize);
        PageTable pt = new PageTable(processId, numPages);
        pageTables.put(processId, pt);

        System.out.println("[GM] Processo " + processId + " criado com " +
                numPages + " páginas virtuais (" + memorySize + " palavras).");
        return true;
    }

    /**
     * Destrói um processo: libera todos os frames alocados e remove a tabela de páginas.
     *
     * @param processId ID do processo a ser removido
     * @return true se o processo foi removido com sucesso
     */
    public boolean destroyProcess(int processId) {
        PageTable pt = pageTables.get(processId);
        if (pt == null) {
            System.out.println("[GM] Erro: Processo " + processId + " não encontrado.");
            return false;
        }

        int framesFreed = 0;
        for (int i = 0; i < pt.getNumPages(); i++) {
            PageTableEntry pte = pt.get(i);
            if (pte.valid) {
                freeFrame(pte.frameNumber);
                pte.valid = false;
                pte.frameNumber = -1;
                framesFreed++;
            }
        }

        pageTables.remove(processId);
        System.out.println("[GM] Processo " + processId + " destruído. " +
                framesFreed + " frame(s) liberado(s).");
        return true;
    }

    /**
     * Verifica se um processo está registrado no gerenciador.
     *
     * @param processId ID do processo
     * @return true se o processo existe
     */
    public boolean hasProcess(int processId) {
        return pageTables.containsKey(processId);
    }

    /**
     * Retorna os IDs de todos os processos registrados.
     */
    public Set<Integer> getProcessIds() {
        return Collections.unmodifiableSet(pageTables.keySet());
    }

    // ========================================================================================
    // =================== TRADUÇÃO DE ENDEREÇOS ==============================================
    // ========================================================================================

    /**
     * Traduz um endereço virtual para um endereço físico.
     *
     * O endereço virtual é decomposto em:
     *   - número da página = endereçoVirtual / tamanhoPágina
     *   - offset           = endereçoVirtual % tamanhoPágina
     *
     * Se a página não estiver carregada (valid == false), ocorre page fault:
     * o gerenciador aloca um frame (possivelmente substituindo outra página)
     * e mapeia a página nesse frame.
     *
     * O endereço físico resultante é: frameNumber * tamanhoPágina + offset.
     *
     * @param processId      ID do processo
     * @param virtualAddress endereço virtual a ser traduzido
     * @return resultado da tradução (endereço físico + indicação de page fault)
     * @throws IllegalArgumentException se o processo não existe ou o endereço é inválido
     */
    public TranslationResult translateAddress(int processId, int virtualAddress) {
        PageTable pt = pageTables.get(processId);
        if (pt == null) {
            throw new IllegalArgumentException(
                    "[GM] Processo " + processId + " não registrado.");
        }

        int pageNumber = virtualAddress / pageSize;
        int offset = virtualAddress % pageSize;

        if (pageNumber < 0 || pageNumber >= pt.getNumPages()) {
            throw new IllegalArgumentException(
                    "[GM] Endereço virtual " + virtualAddress +
                    " fora do espaço do processo " + processId +
                    " (página " + pageNumber + " inválida, máx=" + (pt.getNumPages() - 1) + ").");
        }

        PageTableEntry pte = pt.get(pageNumber);
        boolean fault = false;

        if (!pte.valid) {
            fault = true;
            pageFaultCount++;
            handlePageFault(processId, pageNumber);
        } else {
            pageHitCount++;
        }

        pte.referenced = true;

        int physicalAddress = pte.frameNumber * pageSize + offset;
        return new TranslationResult(physicalAddress, fault);
    }

    /**
     * Marca uma página como modificada (dirty) após uma operação de escrita.
     * Páginas dirty precisam ser salvas em disco antes de serem substituídas.
     *
     * @param processId      ID do processo
     * @param virtualAddress endereço virtual que foi escrito
     */
    public void markDirty(int processId, int virtualAddress) {
        PageTable pt = pageTables.get(processId);
        if (pt == null) return;

        int pageNumber = virtualAddress / pageSize;
        if (pageNumber >= 0 && pageNumber < pt.getNumPages()) {
            pt.get(pageNumber).dirty = true;
        }
    }

    // ========================================================================================
    // =================== TRATAMENTO DE PAGE FAULT ===========================================
    // ========================================================================================

    /**
     * Trata um page fault: encontra ou libera um frame e mapeia a página.
     *
     * Fluxo:
     * 1. Busca um frame livre na tabela de frames.
     * 2. Se não há frame livre, usa o algoritmo Second Chance para selecionar vítima.
     * 3. Se a vítima tem dirty bit ativo, simula write-back para disco.
     * 4. Invalida a entrada antiga e aloca o frame para a nova página.
     *
     * @param processId  ID do processo
     * @param pageNumber número da página que causou o fault
     */
    private void handlePageFault(int processId, int pageNumber) {
        System.out.println("[GM] PAGE FAULT: processo=" + processId +
                ", página=" + pageNumber);

        int frame = findFreeFrame();

        if (frame == -1) {
            frame = selectVictimSecondChance();
            evictPage(frame);
            replacementCount++;
        }

        allocateFrame(frame, processId, pageNumber);

        PageTable pt = pageTables.get(processId);
        PageTableEntry pte = pt.get(pageNumber);
        pte.frameNumber = frame;
        pte.valid = true;
        pte.dirty = false;
        pte.referenced = true;

        System.out.println("[GM] Página " + pageNumber + " do processo " + processId +
                " → frame " + frame +
                " (endereço físico base: " + (frame * pageSize) + ")");
    }

    // ========================================================================================
    // =================== GERENCIAMENTO DE FRAMES ============================================
    // ========================================================================================

    /**
     * Procura um frame livre na tabela de frames (busca linear).
     *
     * @return índice do primeiro frame livre, ou -1 se todos estão ocupados
     */
    private int findFreeFrame() {
        for (int i = 0; i < totalFrames; i++) {
            if (!frameTable[i].occupied) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Aloca um frame para um par (processo, página).
     */
    private void allocateFrame(int frameIndex, int processId, int pageNumber) {
        frameTable[frameIndex].processId = processId;
        frameTable[frameIndex].pageNumber = pageNumber;
        frameTable[frameIndex].occupied = true;
    }

    /**
     * Libera um frame, marcando-o como disponível.
     */
    private void freeFrame(int frameIndex) {
        if (frameIndex >= 0 && frameIndex < totalFrames) {
            frameTable[frameIndex].processId = -1;
            frameTable[frameIndex].pageNumber = -1;
            frameTable[frameIndex].occupied = false;
        }
    }

    /**
     * Retorna a quantidade de frames livres disponíveis.
     */
    public int freeFrameCount() {
        int count = 0;
        for (FrameTableEntry entry : frameTable) {
            if (!entry.occupied) count++;
        }
        return count;
    }

    /**
     * Retorna a quantidade de frames ocupados.
     */
    public int usedFrameCount() {
        return totalFrames - freeFrameCount();
    }

    // ========================================================================================
    // =================== ALGORITMO DE SUBSTITUIÇÃO: SECOND CHANCE (CLOCK) ===================
    // ========================================================================================
    //
    // O algoritmo Second Chance (também chamado de Clock) é uma aproximação do LRU
    // (Least Recently Used) com implementação mais eficiente.
    //
    // Funcionamento:
    //   - Mantém um ponteiro circular (clockPointer) sobre os frames.
    //   - Ao precisar substituir uma página, percorre os frames a partir do ponteiro:
    //       * Se o frame atual tem bit de referência = 0 → é a VÍTIMA (selecionado).
    //       * Se o frame atual tem bit de referência = 1 → recebe "segunda chance":
    //         o bit é limpo (setado para 0) e o ponteiro avança para o próximo.
    //   - No pior caso, dá a volta completa e seleciona o primeiro frame revisitado
    //     (que agora terá referência = 0 pois foi limpo na primeira passagem).
    //
    // Complexidade: O(n) no pior caso por substituição, onde n = totalFrames.
    //
    // Justificativa da escolha:
    //   - Mais simples que LRU puro (não requer timestamps nem reordenação).
    //   - Melhor que FIFO puro (evita a anomalia de Bélády em muitos casos).
    //   - Boa relação custo-benefício para simulações acadêmicas.
    //

    /**
     * Seleciona um frame vítima usando o algoritmo Second Chance (Clock).
     *
     * @return índice do frame selecionado para substituição
     */
    private int selectVictimSecondChance() {
        int iterations = 0;
        int maxIterations = totalFrames * 2; // segurança contra loop infinito

        while (iterations < maxIterations) {
            FrameTableEntry fte = frameTable[clockPointer];

            if (fte.occupied) {
                PageTable pt = pageTables.get(fte.processId);

                if (pt != null && fte.pageNumber < pt.getNumPages()) {
                    PageTableEntry pte = pt.get(fte.pageNumber);

                    if (!pte.referenced) {
                        int victim = clockPointer;
                        clockPointer = (clockPointer + 1) % totalFrames;
                        System.out.println("[GM] Vítima selecionada (Second Chance): frame " + victim +
                                " (processo=" + fte.processId + ", página=" + fte.pageNumber + ")");
                        return victim;
                    } else {
                        pte.referenced = false;
                    }
                }
            }

            clockPointer = (clockPointer + 1) % totalFrames;
            iterations++;
        }

        // Fallback: se por algum motivo o loop não encontrou, retorna o ponteiro atual
        System.out.println("[GM] AVISO: Fallback na seleção de vítima, frame " + clockPointer);
        int victim = clockPointer;
        clockPointer = (clockPointer + 1) % totalFrames;
        return victim;
    }

    /**
     * Despeja (evict) a página residente em um frame.
     * Se a página estava modificada (dirty), simula uma escrita em disco (swap out).
     *
     * @param frameIndex índice do frame a ser despejado
     */
    private void evictPage(int frameIndex) {
        FrameTableEntry fte = frameTable[frameIndex];
        if (!fte.occupied) return;

        PageTable pt = pageTables.get(fte.processId);
        if (pt != null && fte.pageNumber < pt.getNumPages()) {
            PageTableEntry pte = pt.get(fte.pageNumber);

            if (pte.dirty) {
                System.out.println("[GM] Swap out: página " + fte.pageNumber +
                        " do processo " + fte.processId + " (dirty → escrita em disco simulada).");
            }

            pte.valid = false;
            pte.frameNumber = -1;
            pte.dirty = false;
            pte.referenced = false;
        }

        freeFrame(frameIndex);
    }

    // ========================================================================================
    // =================== CONSULTAS E ESTATÍSTICAS ===========================================
    // ========================================================================================

    public PageTable getPageTable(int processId) {
        return pageTables.get(processId);
    }

    public int getPageSize()         { return pageSize; }
    public int getTotalFrames()      { return totalFrames; }
    public int getPhysicalMemSize()  { return physicalMemorySize; }
    public int getPageFaultCount()   { return pageFaultCount; }
    public int getPageHitCount()     { return pageHitCount; }
    public int getReplacementCount() { return replacementCount; }

    /**
     * Calcula a taxa de acerto (hit rate) como percentual.
     *
     * @return taxa de acerto em %, ou 0.0 se nenhum acesso foi feito
     */
    public double getHitRate() {
        int total = pageFaultCount + pageHitCount;
        if (total == 0) return 0.0;
        return (pageHitCount * 100.0) / total;
    }

    /**
     * Reseta todas as estatísticas.
     */
    public void resetStatistics() {
        pageFaultCount = 0;
        pageHitCount = 0;
        replacementCount = 0;
    }

    // ========================================================================================
    // =================== DUMP / DEBUG =======================================================
    // ========================================================================================

    /**
     * Imprime o estado completo do gerenciador de memória virtual,
     * incluindo tabela de frames, tabelas de páginas e estatísticas.
     */
    public void dump() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║       DUMP DO GERENCIADOR DE MEMÓRIA VIRTUAL                ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.printf( "║ Tamanho da página:  %d palavras%n", pageSize);
        System.out.printf( "║ Total de frames:    %d%n", totalFrames);
        System.out.printf( "║ Frames ocupados:    %d%n", usedFrameCount());
        System.out.printf( "║ Frames livres:      %d%n", freeFrameCount());
        System.out.printf( "║ Ponteiro do Clock:  %d%n", clockPointer);
        System.out.println("╠══════════════════════════════════════════════════════════════╣");

        System.out.println("║ TABELA DE FRAMES:");
        for (int i = 0; i < totalFrames; i++) {
            FrameTableEntry fte = frameTable[i];
            String status = fte.occupied ?
                    String.format("processo=%d, página=%d", fte.processId, fte.pageNumber) :
                    "livre";
            System.out.printf( "║   Frame %3d [%5d-%5d]: %s%n",
                    i, i * pageSize, (i + 1) * pageSize - 1, status);
        }

        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║ TABELAS DE PÁGINAS:");
        for (Map.Entry<Integer, PageTable> entry : pageTables.entrySet()) {
            PageTable pt = entry.getValue();
            System.out.printf( "║   Processo %d (%d páginas):%n", pt.processId, pt.getNumPages());
            for (int i = 0; i < pt.getNumPages(); i++) {
                PageTableEntry pte = pt.get(i);
                System.out.printf( "║     Pág %2d: %s%n", i, pte);
            }
        }

        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║ ESTATÍSTICAS:");
        System.out.printf( "║   Page Faults:    %d%n", pageFaultCount);
        System.out.printf( "║   Page Hits:      %d%n", pageHitCount);
        System.out.printf( "║   Substituições:  %d%n", replacementCount);
        System.out.printf( "║   Taxa de acerto: %.2f%%%n", getHitRate());
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    /**
     * Imprime um resumo compacto das estatísticas.
     */
    public void dumpStats() {
        int total = pageFaultCount + pageHitCount;
        System.out.printf("[GM] Estatísticas: %d acessos, %d faults, %d hits (%.2f%%), %d substituições%n",
                total, pageFaultCount, pageHitCount, getHitRate(), replacementCount);
    }
}
