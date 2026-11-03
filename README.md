# Conectoma 3.0

Assistente Android que **raciocina sobre o conectoma real do cérebro da mosca**
(FlyWire FAFB v783). Sem servidor, sem nuvem: tudo roda no aparelho.

| | |
|---|---|
| Neurônios | **34.813** (25,0% dos 139.255 do cérebro completo) |
| Sinapses | **3.750.000** (24,9% das 15.091.983 do dataset) |
| Palavras | **35.311** no léxico, com definições em português |
| APK | 40 MB, assinado, `minSdk 26` (Android 8+), `targetSdk 36` |

## Instalar

1. Copie `Conectoma-3.0.apk` para o celular (cabo, Google Drive, Telegram…).
2. Ao abrir o APK, autorize **"instalar apps desconhecidos"** para o app de origem.
3. Instale e abra. A primeira abertura leva alguns segundos: ele copia o
   conectoma e monta as 3,75 milhões de sinapses na memória.

Não é preciso instalar SDK, Java ou nada no PC. O APK é auto-contido.

## As três telas

**Assistente** — o orbe é o conectoma vivo: cada ponto é um neurônio real, cada
linha uma sinapse real, e os clarões são disparos. Escreva embaixo.

- `o que é sinapse?` → definição + conceitos ligados + o que ele já leu sobre o tema
- `como a música se liga à matemática?` → distância conceitual, vizinhos comuns e lembranças
- `amor é o que move o mundo` → ele grava a definição e reforça as conexões
- **silêncio** → depois de ~30 s sem pergunta, ele puxa assunto sozinho: uma leitura
  antiga, uma ligação forte, uma lacuna de conhecimento ou uma pergunta própria

Cada resposta percorre **89–93% das 3,75 milhões de sinapses** e avalia todas elas
na varredura de plasticidade; o rodapé da mensagem mostra a cobertura do turno.

**Aprender** — cole um URL (Wikipédia funciona muito bem) **ou escolha um PDF**.
Ele lê a página/arquivo, extrai conceitos, cria ligações e aprende palavras novas.
No caso do PDF, as primeiras páginas são renderizadas na tela para você ver o que
ele está lendo. O interruptor de **aprendizado autônomo** faz ele buscar temas
sozinho e continuar mesmo com o app fechado (serviço em primeiro plano).

**Cérebro** — dopamina acumulada, **motor autônomo** (liga/desliga, tarefas,
leituras, pontes e insights), conexões que você fortaleceu, busca no dicionário e
a calculadora de distância conceitual entre duas palavras e o painel de circuitos cognitivos (complexo central, memória de trabalho, corpo cogumelo, neuromodulação).

## O que é real aqui

- **O conectoma é o dado original.** `connections.parquet` e `nodes.parquet`
  foram decodificados e convertidos num grafo CSR de 3,75 M de sinapses; cada
  sinapse carrega a contagem de sinapses e o perfil de neurotransmissores
  (dopamina, GABA, acetilcolina, glutamato) medido no experimento.
- **Os disparos são simulados** com neurônios integra-e-dispara sobre essa
  fiação: inibição vem do GABA, excitação de acetilcolina/glutamato.
- **A plasticidade é STDP de três fatores**: traço de elegibilidade × dopamina
  × dopamina da própria sinapse. O peso de cada sinapse é multiplicado pelo
  aprendizado e salvo entre sessões.
- **A dopamina é um erro de predição de recompensa** (modelo de Schultz): sobe
  quando o resultado supera a expectativa, e é ela que abre a janela de reforço.
- **A semântica usa vetores reais** (Word2Vec CBOW 50d do NILC/USP,
  929 mil palavras), reduzidos às 35.311 do léxico; a distância combina cosseno,
  geodésica no grafo de conceitos aprendido e forma da palavra.
- **PDF é lido no aparelho** por um extrator próprio (`PdfText.kt`, Kotlin puro,
  sem bibliotecas): decodifica FlateDecode, fontes com ToUnicode CMap, strings
  literais/hexadecimais e o posicionamento Td/Tm/TJ. As páginas são renderizadas
  com `PdfRenderer` para visualização.
- **Consolidação (`ConsolidationEngine`)**: todo material lido é costurado ao que
  já existe — cada conceito novo é ancorado nos vizinhos semânticos mais próximos
  (peso ∝ similaridade) e pares distantes que aparecem juntos viram "pontes".
  Sem isso, um PDF deixaria palavras soltas, ligadas só à própria página.
- **Memória episódica (`MemoryEngine`)**: cada URL, PDF ou conversa relevante vira
  um engrama com título, conceitos e até seis frases informativas. Quando o assunto
  volta, o assistente recupera as frases e cita a fonte na resposta — é o que
  transforma “conheço a palavra” em “eu li isso e posso te contar”.
- **Pensamento largo (`ReasoningEngine`/`SpikingNetwork`)**: cada palavra acende
  uma assembleia de ~96 neurônios (antes eram 34). A cada passo, uma amostra de
  ~6% da população também é injetada, então o disparo se espalha pelo conectoma
  inteiro; ao final, uma varredura global aplica STDP de três fatores nas
  **3,75 M de sinapses**. A cobertura do turno aparece na resposta e na tela
  Cérebro.
- **Voz espontânea**: com o interruptor ligado, depois de 30 s de silêncio ele
  fala sozinho a cada ~45 s — lembrança de leitura, ligação recém-fortalecida,
  lacuna que quer estudar ou associação improvável. Também há o botão
  “falar sem eu perguntar”.
- **Motor autônomo (`AutonomousEngine`)**: a pauta não é uma lista fixa. Ele
  escolhe a próxima tarefa do estado atual do conhecimento — consolidar o que
  acabou de entrar, estudar uma **lacuna** (palavra que aprendeu lendo e ainda não
  tem significado, ou palavra comum que nunca entrou em nenhuma ligação) ou
  aproximar dois conceitos distantes quando está sem rede. Roda no app e em
  segundo plano via serviço em primeiro plano.
- **Camada cognitiva (`ConnectomeMapping`/`WorkingMemory`/`CognitionEngine`)**: o complexo
  central (CX, CB.FB*, CB.EB*) vira **memória de trabalho e controle de contexto**, com
  unidades de Izhikevich de 2 variáveis; o corpo cogumelo (MBON, MBIN, Kenyon, DAN/PAM,
  expandido por 1 salto sináptico) vira **associação semântica**. Um `Neuromodulator`
  global (ACh, GABA, DA, 5-HT, NA) ajusta os ganhos de excitação/inibição e o ruído de
  membrana a cada passo, com guarda anti-epilepsia. A hipótese vencedora sai de um
  softmax sobre as respostas reordenadas pela memória de trabalho, e cada resposta fecha
  com um traço curto de cognição + neuromodulação. Sem rede, sem LLM: só spikes e álgebra.
- **As definições vêm do Wikcionário em português** (28 mil verbetes) mais um
  glossário de neurociência curado à mão.

## O que ele não é

Não é um modelo de linguagem. Ele **não tem conhecimento factual do mundo**
(quem descobriu o Brasil, placar de jogo, notícia de hoje). Ele entende
conceitos, definições, relações e distâncias — e aprende o resto com você e com
os URLs que você der. Os limites são honestos: sem internet, o conhecimento é
o do léxico embutido.

## Privacidade

Nenhum dado sai do aparelho por padrão. A rede só é usada quando você manda ler
um URL ou liga o modo autônomo (que consulta resumos do Wikipédia). A memória
aprendida fica em `SharedPreferences`, `files/plastic.bin` e `files/memory.bin`,
no próprio app.

## Construir do zero

Requisitos: JDK 17, Android SDK com `platforms;android-36`. O wrapper já aponta
para o Gradle 8.14.3.

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1
```

O script gera o keystore se ele não existir, compila e deixa o APK em
`Conectoma.apk`. Para assinar com outro keystore, use as variáveis
`CONECTOMA_STORE_PASSWORD`, `CONECTOMA_KEY_ALIAS` e `CONECTOMA_KEY_PASSWORD`.
O keystore **não** é versionado (veja `.gitignore`).

Pipeline de dados (Python 3.12 + numpy) — detalhes em [`tools/README.md`](tools/README.md):

| script | função |
|---|---|
| `tools/parquet_read.py` | leitor Parquet mínimo (ZSTD via libzstd, RLE/bit-packed, dicionário) |
| `tools/build_connectome.py` | seleciona o quarto do cérebro e grava `connectome.bin` |
| `tools/build_pt_dict.py` | extrai definições do dump do Wikcionário |
| `tools/build_lexicon.py` | monta `lexicon.bin` (palavras, definições, vetores, vizinhos) |

Testes de motor (rodam na JVM, sem aparelho):

```powershell
./gradlew testDebugUnitTest
```

16 testes cobrem integridade do grafo, STDP, dopamina, semântica, tokenização e
o diálogo do assistente; somados aos de formatação, PDF, consolidação, memória
episódica e motor autônomo, são **33 testes**.

## Créditos e licenças

- Conectoma: **FlyWire FAFB v783** — Dorkenwald et al., *Nature* 2024
  ([doi:10.1038/s41586-024-07558-y](https://doi.org/10.1038/s41586-024-07558-y));
  anotações de Schlegel et al., *Nature* 2024
  ([doi:10.1038/s41586-024-07686-5](https://doi.org/10.1038/s41586-024-07686-5)).
  Dados CC-BY-4.0.
- Definições: Wikcionário em português, CC BY-SA 3.0.
- Vetores: NILC/USP (Word2Vec CBOW 50d), uso pessoal e educacional.
- Ícone: arte original fornecida pelo autor do projeto.
