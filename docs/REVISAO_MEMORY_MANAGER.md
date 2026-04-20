# Revisão de Código: MemoryManager

> Revisão da implementação da classe `MemoryManager.java` com base no enunciado do Gerenciador de Memória (paginação).

---

## 1. Problemas Identificados no `allocate`

### 1.1 Arredondamento na divisão de páginas

**Localização:** `MemoryManager.java`, linha 34

```java
// Atual (incorreto)
int numPages = wordsSize / pageSize;
```

A divisão inteira trunca para baixo. Se `wordsSize = 17` e `pageSize = 16`, `numPages = 1`, mas são necessárias **2 páginas**.

**Correção:**

```java
int numPages = (wordsSize + pageSize - 1) / pageSize;
```

---

### 1.2 Loop interno sem `break`

**Localização:** `MemoryManager.java`, linhas 42-47

```java
// Atual (incorreto)
for (int i = 0; i < numPages; i++) {
    for (int j = 0; j < frameControl.length; j++) {
        if (memory.pos[j * pageSize] == null) {
            pages[i] = new Page(frameControl[j], pageSize);
        }
    }
}
```

**Problemas:**

- O loop interno percorre **todos** os frames sem parar ao encontrar o primeiro livre.
- `pages[i]` é sobrescrito repetidamente, resultando sempre no **último** frame livre em vez do primeiro.

---

### 1.3 Frames nunca marcados como ocupados

Após associar um frame a uma página, o código **não** executa `frameControl[j].isOccupied = true`. Consequências:

- O mesmo frame pode ser atribuído a **múltiplas páginas**.
- `getAvailableFrames()` sempre retorna todos os frames como disponíveis, invalidando a checagem de espaço.

---

### 1.4 Verificação de disponibilidade inconsistente

**Localização:** `MemoryManager.java`, linha 44

```java
// Atual — usa posição de memória para checar disponibilidade
if (memory.pos[j * pageSize] == null)
```

O campo `Frame.isOccupied` existe justamente para esse controle. A checagem deveria usar `!frameControl[j].isOccupied` para manter consistência com `getAvailableFrames()` e `deallocate`.

---

## 2. Observação sobre a Interface

O enunciado sugere a seguinte assinatura:

```
Boolean aloca(IN int nroPalavras, OUT tabelaPaginas []int)
Void desaloca(IN tabelaPaginas []int)
```

A implementação atual retorna `Page[]` e recebe `Page[]`, o que é uma adaptação OOP válida. Porém, o enunciado espera que a tabela de páginas contenha os **índices dos frames** (inteiros), pois será usada na tradução de endereços (seção 1.4 do enunciado). Avaliar se o restante do sistema espera essa interface em forma de inteiros ou objetos.

---

## 3. Código Corrigido Sugerido

### `allocate`

```java
public Page[] allocate(int wordsSize) {
    int numPages = (wordsSize + pageSize - 1) / pageSize;

    Frame[] available = getAvailableFrames();
    if (numPages > available.length) {
        return null;
    }

    Page[] pages = new Page[numPages];

    for (int i = 0; i < numPages; i++) {
        available[i].isOccupied = true;
        pages[i] = new Page(available[i], pageSize);
    }

    return pages;
}
```

**Melhorias aplicadas:**

- **Arredondamento para cima** no cálculo de páginas
- **Uso direto de `getAvailableFrames()`** — elimina o loop duplo aninhado
- **Marcação de frames como ocupados** imediatamente após alocação
- **Cada página recebe um frame distinto**

---

## 4. Análise do `deallocate`

A implementação atual do `deallocate` está **correta** em relação ao enunciado:

```java
public void deallocate(Page[] pages) {
    for (Page page : pages) {
        page.words = null;
        page.frame.isOccupied = false;

        for (int i = page.frame.start; i <= page.frame.end; i++) {
            memory.pos[i] = null;
        }
    }
}
```

- Libera as palavras da página
- Marca o frame como desocupado
- Limpa as posições de memória física correspondentes

---

*Documento de revisão — Projeto Sistemas Operacionais, PUCRS.*
