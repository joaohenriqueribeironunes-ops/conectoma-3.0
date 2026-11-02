# Pipeline de dados

Estes scripts transformam os arquivos originais do FlyWire e do Wikcionário nos
dois assets que o app carrega:

```
app/src/main/assets/connectome.bin   26 MB   3.750.000 sinapses em CSR
app/src/main/assets/nodes.tsv       2,5 MB   34.813 neurônios anotados
app/src/main/assets/lexicon.bin     6,1 MB   35.311 palavras + vetores int8
```

## Ordem de execução

| # | script | entrada | saída |
|---|---|---|---|
| 1 | `build_connectome.py` | `nodes.parquet`, `connections.parquet` | `connectome.bin`, `nodes.tsv`, `conectoma_stats.json` |
| 2 | `build_pt_dict.py` | dump do Wikcionário PT (baixa 70 MB) | `data/pt_defs.tsv` |
| 3 | `build_lexicon.py` | `pt_defs.tsv`, `dictionary.jsonl`, `nilc_emb.npy`, `vocab.txt` | `lexicon.bin` |
| 4 | `make_icons.py` | arte original do ícone | `res/mipmap-*/ic_launcher.png` |

Apoio:

- `parquet_read.py` — leitor Parquet mínimo (ZSTD via `libzstd`, RLE/bit-packed,
  dicionário). Não depende de pyarrow.
- `download_dict.py` / `download_emb.py` — baixam o corpus do Wikcionário e os
  vetores do NILC.
- `extract_dict.py` / `refine_dict.py` — filtram o dump do enWiktionary para o
  léxico de apoio em português.
- `build_lexicon.py` já inclui o glossário curado `data/domain.tsv`.

## Caminhos

Os scripts têm os caminhos desta máquina no topo (variável `NODES`, `CONN`,
`DEST`, `DLL`). Ajuste-os se rodar em outro lugar. O `.bin` do zstd é carregado
de `C:\Program Files\Git\mingw64\bin\libzstd.dll`; qualquer build do libzstd
1.5+ serve.
