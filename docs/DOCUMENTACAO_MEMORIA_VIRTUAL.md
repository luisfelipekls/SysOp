# Documentação: Gerenciador de Memória Virtual

## Índice

1. [Visão Geral](#1-visão-geral)
2. [Arquitetura e Estruturas de Dados](#2-arquitetura-e-estruturas-de-dados)
3. [Funcionamento Detalhado](#3-funcionamento-detalhado)
4. [Algoritmo de Substituição: Second Chance (Clock)](#4-algoritmo-de-substituição-second-chance-clock)
5. [Justificativa das Escolhas de Projeto](#5-justificativa-das-escolhas-de-projeto)
6. [Exemplo de Uso (Standalone)](#6-exemplo-de-uso-standalone)
7. [Proposta de Integração com a Classe Sistema](#7-proposta-de-integração-com-a-classe-sistema)
8. [Relação com os Arquivos Existentes](#8-relação-com-os-arquivos-existentes)

---

## 1. Visão Geral

A classe `GerenciadorMemoriaVirtual` (pacote `memoria`) implementa um **gerenciador de memória virtual baseado em paginação**, projetado para ser compatível com o simulador de Sistema Operacional presente na classe `Sistema`.

### Funcionalidades principais

- **Paginação**: a memória física é dividida em **frames** de tamanho fixo; a memória lógica de cada processo é dividida em **páginas** do mesmo tamanho.
- **Demand Paging**: páginas são carregadas em memória física apenas quando acessadas pela primeira vez (page fault controlado).
- **Tabela de Páginas por processo**: cada processo possui sua própria tabela que mapeia páginas virtuais → frames físicos.
- **Tabela de Frames global**: controle centralizado de quais frames estão livres ou ocupados (e por quem).
- **Tradução de endereços**: converte endereços virtuais em endereços físicos com detecção de page fault.
- **Substituição de páginas**: quando a memória está cheia, o algoritmo **Second Chance (Clock)** seleciona a página vítima.
- **Estatísticas**: contagem de page faults, page hits, substituições e taxa de acerto.

---

## 2. Arquitetura e Estruturas de Dados

### 2.1 Diagrama Conceitual

```
┌─────────────────────────────────────────────────────────────────┐
│                  GerenciadorMemoriaVirtual                       │
│                                                                  │
│  ┌─────────────────────┐      ┌──────────────────────────────┐  │
│  │  Map<pid, PageTable> │      │  FrameTableEntry[totalFrames]│  │
│  │                      │      │                              │  │
│  │  Processo 1:         │      │  Frame 0: [pid=1, pág=0]    │  │
│  │   Pág 0 → Frame 2   │─────►│  Frame 1: [livre]           │  │
│  │   Pág 1 → Frame 0   │      │  Frame 2: [pid=1, pág=0]    │  │
│  │   Pág 2 → inválida  │      │  Frame 3: [pid=2, pág=1]    │  │
│  │                      │      │  ...                         │  │
│  │  Processo 2:         │      │                              │  │
│  │   Pág 0 → inválida  │      │  clockPointer ──► Frame 1    │  │
│  │   Pág 1 → Frame 3   │      └──────────────────────────────┘  │
│  └─────────────────────┘                                         │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 Estruturas Internas

| Estrutura | Descrição |
|-----------|-----------|
| `PageTableEntry` | Entrada da tabela de páginas: `frameNumber`, `valid`, `dirty`, `referenced` |
| `FrameTableEntry` | Entrada da tabela de frames: `processId`, `pageNumber`, `occupied` |
| `PageTable` | Encapsula o array de `PageTableEntry` de um processo |
| `TranslationResult` | Resultado de tradução: `physicalAddress`, `pageFault` |

### 2.3 Atributos Principais

| Atributo | Tipo | Descrição |
|----------|------|-----------|
| `pageSize` | `int` | Tamanho de cada página/frame (em palavras) |
| `totalFrames` | `int` | Número de frames na memória física |
| `frameTable` | `FrameTableEntry[]` | Tabela global de frames |
| `pageTables` | `Map<Integer, PageTable>` | Tabelas de páginas (uma por processo) |
| `clockPointer` | `int` | Ponteiro circular do algoritmo Second Chance |

---

## 3. Funcionamento Detalhado

### 3.1 Ciclo de Vida de um Processo

```
createProcess(pid, tamanho)    →  Cria tabela de páginas (todas as entradas inválidas)
       │
       ▼
translateAddress(pid, endVirt)  →  Traduz endereço virtual para físico
       │                            │
       │    ┌── valid == true ──────►│  Page HIT: retorna endereço físico
       │    │                        │
       │    └── valid == false ─────►│  PAGE FAULT:
       │                             │    1. Busca frame livre
       │                             │    2. Se cheio → Second Chance seleciona vítima
       │                             │    3. Se vítima dirty → simula swap out
       │                             │    4. Aloca frame → atualiza tabelas
       │                             │    5. Retorna endereço físico
       ▼
destroyProcess(pid)             →  Libera todos os frames, remove tabela de páginas
```

### 3.2 Tradução de Endereço

Dado um endereço virtual `V` e tamanho de página `P`:

```
página  = V / P       (divisão inteira)
offset  = V % P       (resto da divisão)

endereço_físico = tabela_paginas[página].frameNumber * P + offset
```

**Exemplo** com `pageSize = 16` e endereço virtual `35`:
- Página = 35 / 16 = **2**
- Offset = 35 % 16 = **3**
- Se a página 2 está mapeada no frame 5: endereço físico = 5 × 16 + 3 = **83**

### 3.3 Bits de Controle

| Bit | Significado | Quando é setado | Quando é limpo |
|-----|-------------|-----------------|----------------|
| `valid` | Página presente em memória física | Ao carregar a página em um frame | Ao ser despejada (evict) |
| `dirty` | Página foi modificada (escrita) | Ao chamar `markDirty()` | Após swap out ou ao ser despejada |
| `referenced` | Página foi acessada recentemente | A cada tradução de endereço | Pelo algoritmo Second Chance |

---

## 4. Algoritmo de Substituição: Second Chance (Clock)

### 4.1 Descrição

O **Second Chance** (também chamado **Clock**) é uma variação do FIFO que utiliza um **bit de referência** para evitar a remoção de páginas recentemente acessadas.

### 4.2 Funcionamento

```
                    clockPointer
                         │
                         ▼
         ┌───┐  ┌───┐  ┌───┐  ┌───┐  ┌───┐
         │ 0 │──│ 1 │──│ 0 │──│ 1 │──│ 0 │──┐
         └───┘  └───┘  └───┘  └───┘  └───┘  │
           ▲                                  │
           └──────────────────────────────────┘
              (ponteiro circular sobre os frames)
```

1. O ponteiro examina o frame atual:
   - **ref = 0** → Este frame é a **vítima**. Seleciona para substituição.
   - **ref = 1** → Dá **segunda chance**: limpa o bit (ref = 0) e avança o ponteiro.
2. No pior caso, percorre todos os frames duas vezes (uma para limpar bits, outra para selecionar).

### 4.3 Comparação com Alternativas

| Algoritmo | Prós | Contras |
|-----------|------|---------|
| **FIFO** | Simples | Anomalia de Bélády; não considera uso recente |
| **LRU** | Ótimo em localidade | Requer timestamps/contadores; custo alto |
| **Second Chance** | Boa aproximação do LRU; simples | O(n) no pior caso por substituição |
| **Ótimo** | Melhor taxa de faults | Impossível de implementar (requer conhecimento futuro) |

**Escolha**: Second Chance oferece o **melhor equilíbrio entre simplicidade de implementação e eficiência**, sendo amplamente utilizado em SOs reais e ideal para uma simulação acadêmica.

---

## 5. Justificativa das Escolhas de Projeto

### 5.1 Paginação com tamanho fixo

- **Por quê**: Eliminação de fragmentação externa; gerenciamento simples; alinhamento natural com a memória do `Sistema` (array de `Word[]`).
- **Trade-off**: Pode causar fragmentação interna na última página de cada processo (aceitável para a escala da simulação).

### 5.2 Demand Paging

- **Por quê**: Não aloca frames até que sejam realmente necessários, permitindo que o espaço de endereçamento virtual do processo seja maior que a memória física disponível.
- **Benefício**: Múltiplos processos podem coexistir mesmo com memória física limitada.

### 5.3 Tabela de páginas por processo (HashMap)

- **Por quê**: Cada processo precisa de isolamento no seu espaço de endereçamento. O `HashMap<Integer, PageTable>` permite acesso O(1) à tabela de qualquer processo.
- **Alternativa descartada**: Tabela de páginas invertida (mais complexa, menor benefício para a escala da simulação).

### 5.4 Tabela de frames como array

- **Por quê**: O número de frames é fixo e conhecido na inicialização. Array oferece acesso O(1) por índice e representa fielmente a memória física.

### 5.5 Classe autocontida (sem dependência de Sistema)

- **Por quê**: Desacopla o gerenciador de memória da simulação de CPU/HW, permitindo testes independentes e futura integração flexível. A classe não importa nada de `system.Sistema`, diferentemente do `MemoryManager.java` existente.

---

## 6. Exemplo de Uso (Standalone)

```java
import memoria.GerenciadorMemoriaVirtual;
import memoria.GerenciadorMemoriaVirtual.TranslationResult;

public class ExemploMemoriaVirtual {
    public static void main(String[] args) {
        // Memória física de 1024 palavras, páginas de 64 palavras = 16 frames
        GerenciadorMemoriaVirtual gm = new GerenciadorMemoriaVirtual(1024, 64);

        // Cria processo com espaço virtual de 256 palavras (4 páginas)
        gm.createProcess(1, 256);

        // Cria outro processo com 192 palavras (3 páginas)
        gm.createProcess(2, 192);

        // Acessa endereço virtual 100 do processo 1 (página 1, offset 36)
        TranslationResult r1 = gm.translateAddress(1, 100);
        System.out.println("Endereço físico: " + r1.getPhysicalAddress());
        System.out.println("Page fault? " + r1.hadPageFault());   // true (primeiro acesso)

        // Segundo acesso à mesma página → hit
        TranslationResult r2 = gm.translateAddress(1, 110);
        System.out.println("Page fault? " + r2.hadPageFault());   // false (hit)

        // Marca escrita no endereço 100 do processo 1
        gm.markDirty(1, 100);

        // Dump completo
        gm.dump();

        // Estatísticas
        gm.dumpStats();

        // Destrói processo 2
        gm.destroyProcess(2);
    }
}
```

---

## 7. Proposta de Integração com a Classe Sistema

### 7.1 Visão Geral da Integração

A integração do `GerenciadorMemoriaVirtual` com a classe `Sistema` requer modificações em três pontos principais: **CPU**, **SO** e **Utilities**.

### 7.2 Modificações Propostas

#### 7.2.1 Na classe `SO` — instanciar o gerenciador

```java
public class SO {
    public InterruptHandling ih;
    public SysCallHandling sc;
    public Utilities utils;
    public GerenciadorMemoriaVirtual gm;  // NOVO

    public SO(HW hw) {
        ih = new InterruptHandling(hw);
        sc = new SysCallHandling(hw);
        hw.cpu.setAddressOfHandlers(ih, sc);
        utils = new Utilities(hw);

        // Memória de 1024 palavras, páginas de 64 palavras
        gm = new GerenciadorMemoriaVirtual(hw.mem.pos.length, 64);  // NOVO
    }
}
```

#### 7.2.2 Na classe `CPU` — traduzir endereços antes do acesso à memória

O método `legal(int e)` atual verifica apenas se o endereço está nos limites da memória. Com memória virtual, ele deve:

1. Receber o endereço **virtual** do processo corrente.
2. Chamar `gm.translateAddress(pid, endereçoVirtual)` para obter o endereço **físico**.
3. Usar o endereço físico para acessar `m[]`.

```java
// Exemplo conceitual - modificação no método legal e nos acessos à memória
private int translate(int virtualAddr) {
    TranslationResult tr = gm.translateAddress(currentProcessId, virtualAddr);
    return tr.getPhysicalAddress();
}
```

Cada instrução que acessa memória (`LDD`, `LDX`, `STD`, `STX`) usaria `translate()` em vez do endereço direto.

#### 7.2.3 Na classe `Utilities` — `loadProgram` com alocação paginada

Em vez de carregar o programa na posição 0 da memória diretamente, o `loadProgram` deveria:

1. Chamar `gm.createProcess(pid, programa.length)` para registrar o processo.
2. Para cada palavra do programa, traduzir o endereço virtual para físico e escrever na posição correta.
3. Marcar as páginas escritas como dirty.

```java
private void loadProgram(int processId, Word[] p) {
    gm.createProcess(processId, p.length);
    for (int i = 0; i < p.length; i++) {
        TranslationResult tr = gm.translateAddress(processId, i);
        int phys = tr.getPhysicalAddress();
        m[phys].opc = p[i].opc;
        m[phys].ra  = p[i].ra;
        m[phys].rb  = p[i].rb;
        m[phys].p   = p[i].p;
        gm.markDirty(processId, i);
    }
}
```

#### 7.2.4 Nova interrupção — Page Fault

Adicionar `intPageFault` ao enum `Interrupts` para que page faults possam ser tratados pelo mecanismo de interrupções existente (opcional — o gerenciador já trata internamente, mas expor via interrupção seria mais realista).

#### 7.2.5 Contexto do processo

A CPU precisaria armazenar o `processId` do processo em execução para que a tradução de endereços funcione. Isso pode ser adicionado ao contexto (`setContext`):

```java
public void setContext(int _pc, int _processId) {
    pc = _pc;
    currentProcessId = _processId;
    irpt = Interrupts.noInterrupt;
}
```

### 7.3 Diagrama de Integração

```
┌─────────────────────────────────────────────────────────────┐
│                         Sistema                              │
│                                                              │
│  ┌─────────── HW ───────────┐  ┌──────── SO ─────────────┐ │
│  │                           │  │                          │ │
│  │  Memory (Word[1024])      │  │  InterruptHandling       │ │
│  │       ▲                   │  │  SysCallHandling         │ │
│  │       │ endereço físico   │  │  Utilities               │ │
│  │       │                   │  │                          │ │
│  │  CPU ─┤                   │  │  GerenciadorMemoria  ◄───┤ │
│  │    │  │                   │  │   Virtual (NOVO)         │ │
│  │    │  │                   │  │    │                     │ │
│  │    └──┼── end. virtual ──►│  │    ├─ PageTable (pid 1)  │ │
│  │       │   tradução        │  │    ├─ PageTable (pid 2)  │ │
│  │       │                   │  │    └─ FrameTable         │ │
│  └───────────────────────────┘  └──────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

### 7.4 Ordem Sugerida de Implementação

1. Instanciar `GerenciadorMemoriaVirtual` dentro de `SO`.
2. Adicionar `currentProcessId` ao contexto da CPU.
3. Criar método `translate()` na CPU que chama o gerenciador.
4. Substituir acessos diretos a `m[endereço]` por `m[translate(endereço)]` nas instruções `LDD`, `LDX`, `STD`, `STX`.
5. Modificar `loadProgram` para usar alocação paginada.
6. (Opcional) Adicionar interrupção `intPageFault` ao enum `Interrupts`.
7. Testar com os programas existentes (fatorial, fibonacci, etc.).

---

## 8. Relação com os Arquivos Existentes

| Arquivo | Status | Relação |
|---------|--------|---------|
| `GerenciadorMemoriaVirtual.java` | **NOVO** | Implementação completa do gerenciador |
| `MemoryManager.java` | Existente (incompleto) | Não modificado; abordagem inicial diferente (acesso direto ao `Memory` de `Sistema`) |
| `Page.java` | Existente | Não utilizado pelo novo gerenciador (estrutura interna própria via `PageTableEntry`) |
| `Frame.java` | Existente | Não utilizado pelo novo gerenciador (estrutura interna própria via `FrameTableEntry`) |
| `Sistema.java` | Existente | **Não modificado** — integração proposta na seção 7 |

### Por que não reutilizar `Page.java` e `Frame.java`?

As classes existentes (`Page` e `Frame`) foram projetadas com dependência direta de `system.Sistema.Word` e `system.Sistema.Memory`. O novo gerenciador foi intencionalmente desacoplado dessas classes internas do `Sistema` para:

- Permitir **testes independentes** sem instanciar todo o hardware simulado.
- Evitar **acoplamento circular** entre pacotes.
- Manter a **flexibilidade** de integração (o gerenciador trabalha com índices numéricos, não com referências diretas à memória).

---

*Documento gerado como parte do projeto de Sistemas Operacionais — PUCRS, Escola Politécnica.*
