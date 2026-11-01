# Conectoma 3.0

App Android em **Kotlin** (Jetpack Compose) que simula um conectoma em escala de *Drosophila*, com estética **Liquid Glass do iOS 27**, aprendizado por fala e URL, plasticidade Hebbiana e um ciclo autônomo — sem exigir que você instale Android SDK, JDK ou Gradle no PC. O APK é gerado no GitHub Actions.

## O que este app é (e o que não é)

Não é um cérebro biológico nem um LLM. É um **motor de grafo esparso** no aparelho:

| Grandeza | Valor | Origem |
| --- | --- | --- |
| Neurônios | 34 813 (¼ de 139 255) | FlyWire, cérebro adulto de *Drosophila melanogaster* (Dorkenwald et al., *Nature* 2024) |
| Sinapses | 3 750 000 arestas dirigidas | pedido do projeto; família estatística do FlyWire (rich-club ~30%, clustering local ~71%) |
| Transmissores | ACh, GABA, glutamato, dopamina, octopamina, serotonina | anotações do conectoma da mosca |
| Aprendizado associativo | assembleias estilo neurônios de Kenyon / corpo de cogumelo | literatura da Drosophila |

Cada palavra vira uma **assembleia** de neurônios. Quando duas ideias se alinham **semanticamente** (cosseno de embeddings de n-gramas) e **matematicamente** (Jaccard, Levenshtein, hops no grafo, log-distância se forem números), o **motor sináptico** dispara as assembleias juntas e o **motor de neuroplasticidade** reforça as arestas (Hebb). Se o SNC simulado “sente” o entendimento, dispara um **impulso** e um pulso **dopaminérgico** na UI.

## Motores

1. **Grafo sináptico** — 3,75 M de conexões em arrays compactos (sem um objeto por sinapse).
2. **Motor semântico** — distância conceitual em 64 dimensões.
3. **Motor matemático** — distância quantitativa + caminho no conectoma.
4. **Motor sináptico** — co-ativação e propagação de impulso.
5. **Neuroplasticidade** — Hebb + decaimento homeostático.
6. **Dopamina** — recompensa só quando semântica, matemática e Δpeso cruzam limiar.
7. **Dicionário vivo** — léxico inicial PT/EN + crescimento por fala, páginas e Wiktionary/Wikipedia.
8. **URL** — baixa a página, limpa HTML, tokeniza, integra.
9. **Autônomo** — a cada ~25 min escolhe um par *distante*, busca definições/imagens e tenta uma ponte.
10. **HyperOS 1** — `minSdk`/`targetSdk` alinhados ao guia Xiaomi (piso 23, alvo 34), meta-data HyperOS, boot MIUI, WorkManager. Sem SDK proprietário (não pede App Id nem instala nada no PC).

## Interface

Estética **iOS 27 / Liquid Glass**: vidro translúcido, borda escura, brilho especular, cantos ~28 dp, barra flutuante, pulso rosa quando há compreensão.

## APK

O workflow `.github/workflows/release.yml` gera `conectoma-3.0.apk` e, em tags `v*`, publica na Release.

Instalação no telefone: ative fontes desconhecidas / instalar via GitHub. Assinatura de demonstração (keystore debug do CI), não é a Play Store.

## Uso

1. Abra o app — na primeira vez ele tece as 3,75 M de sinapses.
2. Fale ou escreva; o léxico cresce.
3. Cole uma URL (Wikipedia funciona bem).
4. Toque em **autônomo** para um ciclo imediato de conceitos distantes.
5. A aba **OS** mostra se o aparelho reporta HyperOS.

## Referências

- Dorkenwald et al., *Neuronal wiring diagram of an adult brain*, Nature 2024 (139 255 neurônios, ~54,5 M sinapses químicas).
- Lin et al., *Network statistics of the whole-brain connectome of Drosophila*, Nature 2024 (rich-club ~30%, grau médio ~20,5 no grafo limiarizado).
- Documentação Xiaomi HyperOS: minSdk 23, targetSdk 34.
- Apple WWDC 2026: Liquid Glass no iOS 27 (difusão, borda escura, highlight, slider de transparência).
