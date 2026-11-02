"""Build lexicon.bin from the NILC Portuguese embeddings + the Wiktionary dictionary."""
import json, os, struct, time, unicodedata
from collections import defaultdict

import numpy as np

DEST = r"C:\Users\conta\OneDrive\Documentos\deepseek-harness\default-workspace\tools\data"
ROOT = r"C:\Users\conta\OneDrive\Documentos\deepseek-harness\default-workspace"
OUT = os.path.join(ROOT, "conectoma", "app", "src", "main", "assets", "lexicon.bin")
KNN = 8
MAX_WORDS = 40000

t0 = time.time()
rows = []
with open(os.path.join(DEST, "lexicon.tsv"), encoding="utf-8") as f:
    for line in f:
        p = line.rstrip("\n").split("\t")
        if len(p) >= 6:
            rows.append((p[0], p[1], p[2], p[3], int(p[4] or 0), p[5]))

relations = defaultdict(lambda: {"s": [], "h": []})
with open(os.path.join(DEST, "dictionary.jsonl"), encoding="utf-8") as f:
    for line in f:
        o = json.loads(line)
        relations[o["w"]]["s"].extend(o.get("s") or [])
        relations[o["w"]]["h"].extend(o.get("h") or [])

pt_defs = {}
pt_path = os.path.join(DEST, "pt_defs.tsv")
if os.path.exists(pt_path):
    for line in open(pt_path, encoding="utf-8"):
        p = line.rstrip("\n").split("\t")
        if len(p) == 2:
            pt_defs[p[0]] = p[1]


def tidy_pt(raw):
    parts = []
    for seg in raw.split("||"):
        s = seg.strip().strip("*").strip()
        if not s or s.startswith("*") or s.startswith("(") or s.startswith("\u2192"):
            continue
        if "{{" in s or "[[" in s:
            continue
        parts.append(s)
    return "; ".join(parts[:2])[:280] if parts else ""


base = {}
order = []
for w, lem, pos, definition, freq, syn in rows:
    if w not in base:
        order.append(w)
    base[w] = [lem, pos, definition, freq, syn]

freq_map = {}
for line in open(os.path.join(DEST, "pt_50k.txt"), encoding="utf-8"):
    p = line.rsplit(" ", 1)
    if len(p) == 2:
        try:
            freq_map[p[0].strip().lower()] = int(p[1])
        except ValueError:
            pass

# every word that has a Portuguese definition belongs in the lexicon
for w, raw in pt_defs.items():
    if w in base:
        continue
    d = tidy_pt(raw)
    if not d:
        continue
    base[w] = ["", "noun", d, freq_map.get(w, 40), ""]
    order.append(w)

# curated domain glossary (neuroscience / computation vocabulary)
DOMAIN = os.path.join(DEST, "domain.tsv")
if os.path.exists(DOMAIN):
    for line in open(DOMAIN, encoding="utf-8"):
        p = line.rstrip("\n").split("\t")
        if len(p) < 3:
            continue
        w, pos, d = p[0], p[1], p[2]
        pos = {"subst": "noun", "subst.": "noun", "adj.": "adj", "adj": "adj"}.get(pos, "noun")
        if w in base:
            base[w][2] = d
            base[w][1] = pos
            base[w][3] = max(base[w][3], 6200)
        else:
            base[w] = ["", pos, d, 6200, ""]
            order.append(w)

merged = []
for w in order:
    lem, pos, d, freq, syn = base[w]
    if pt_defs.get(w):
        t = tidy_pt(pt_defs[w])
        if t:
            d = t
    merged.append([w, lem, pos, d[:280], freq, syn])

merged.sort(key=lambda r: (-r[4], r[0]))
merged = merged[:MAX_WORDS]
words = [r[0] for r in merged]
wid = {w: i for i, w in enumerate(words)}
N = len(words)
print("words", N, flush=True)


def strip_accents(s):
    return "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn")


V = np.load(os.path.join(DEST, "nilc_emb.npy"))
with open(os.path.join(DEST, "vocab.txt"), encoding="utf-8") as f:
    vocab = [l.strip() for l in f]
vidx = {}
for i, w in enumerate(vocab):
    vidx.setdefault(w, i)
DIM = V.shape[1]
print("nilc", V.shape, flush=True)

emb = np.zeros((N, DIM), dtype=np.float32)
missing = []
for i, w in enumerate(words):
    j = vidx.get(w)
    if j is None:
        j = vidx.get(strip_accents(w))
    if j is not None:
        emb[i] = V[j]
    else:
        missing.append(i)

# fill gaps from the surrounding gloss words
plain = {}
for i, w in enumerate(words):
    plain.setdefault(strip_accents(w), i)
for i in missing:
    d = merged[i][3]
    acc = np.zeros(DIM, dtype=np.float32)
    n = 0
    for t in d.lower().replace(",", " ").replace(";", " ").split():
        j = vidx.get(t) or vidx.get(strip_accents(t))
        if j is not None:
            acc += V[j]
            n += 1
    if n:
        emb[i] = acc / n
    else:
        src = merged[i][1]
        j = wid.get(src) or plain.get(strip_accents(src)) if src else None
        if j is not None and j != i:
            emb[i] = emb[j] if not np.allclose(emb[j], 0) else 0
    if np.allclose(emb[i], 0):
        rng = np.random.default_rng(i)
        emb[i] = rng.standard_normal(DIM).astype(np.float32) * 0.001
print("filled missing", len(missing), flush=True)

# anchor the domain vocabulary to concepts the corpus already knows
ANCHORS = {
    "sinapse": ["neurotransmissor", "impulso", "neurônio"],
    "neurônio": ["célula", "nervo", "cérebro"],
    "conectoma": ["cérebro", "rede", "mapa"],
    "dopamina": ["neurotransmissor", "recompensa", "prazer"],
    "plasticidade": ["mudança", "adaptação", "aprendizado"],
    "cérebro": ["órgão", "mente", "nervoso"],
    "aprendizado": ["conhecimento", "estudo", "memória"],
    "memória": ["lembrança", "recordação", "cérebro"],
    "entropia": ["desordem", "caos", "energia"],
    "raciocínio": ["pensamento", "lógica", "inteligência"],
    "algoritmo": ["cálculo", "processo", "programa"],
    "conceito": ["ideia", "noção", "pensamento"],
}
for w, anchors in ANCHORS.items():
    i = wid.get(w)
    if i is None:
        continue
    acc = np.zeros(DIM, dtype=np.float32)
    n = 0
    for a in anchors:
        jj = vidx.get(a)
        if jj is not None:
            acc += V[jj]
            n += 1
            continue
        wi = wid.get(a)
        if wi is not None and wi != i and np.linalg.norm(emb[wi]) > 1e-6:
            acc += emb[wi]
            n += 1
    if n:
        anchored = acc / n
        if np.linalg.norm(emb[i]) < 1e-6:
            emb[i] = anchored
        else:
            emb[i] = 0.45 * emb[i] + 0.55 * anchored
print("anchored domain terms", flush=True)

nrm = np.linalg.norm(emb, axis=1, keepdims=True)
nrm[nrm < 1e-6] = 1.0
emb /= nrm

neighbors = np.full((N, KNN), -1, dtype=np.int32)
CH = 1000
for start in range(0, N, CH):
    stop = min(N, start + CH)
    block = emb[start:stop] @ emb.T
    for r in range(stop - start):
        row = block[r]
        idx = np.argpartition(-row, KNN + 1)[:KNN + 1]
        idx = idx[np.argsort(-row[idx])]
        picked = [int(x) for x in idx if x != start + r][:KNN]
        neighbors[start + r, :len(picked)] = picked
print("knn done", round(time.time() - t0, 1), flush=True)

maxabs = np.abs(emb).max(axis=1)
scale = maxabs / 127.0
scale[scale < 1e-6] = 1e-6
q = np.clip(np.round(emb / scale[:, None]), -127, 127).astype(np.int8)

word_bytes = [w.encode("utf-8") for w in words]
def_bytes = [r[3].encode("utf-8") for r in merged]
lemma_ids = np.array([wid.get(r[1], -1) if r[1] else -1 for r in merged], dtype=np.int32)
POS = {"noun": 1, "verb": 2, "adj": 3, "adv": 4, "name": 5, "pron": 6, "prep": 7, "conj": 8, "num": 9}
pos_arr = np.array([POS.get(r[2], 0) for r in merged], dtype=np.uint8)
freq_arr = np.array([r[4] for r in merged], dtype=np.int32)

word_off = np.zeros(N + 1, dtype=np.uint32)
for i, b in enumerate(word_bytes):
    word_off[i + 1] = word_off[i] + len(b)
def_off = np.zeros(N + 1, dtype=np.uint32)
for i, b in enumerate(def_bytes):
    def_off[i + 1] = def_off[i] + len(b)

with open(OUT, "wb") as f:
    f.write(b"LEXC")
    f.write(struct.pack("<IIIII", 1, N, DIM, KNN, 0))
    f.write(word_off.tobytes())
    f.write(b"".join(word_bytes))
    f.write(def_off.tobytes())
    f.write(b"".join(def_bytes))
    f.write(lemma_ids.tobytes())
    f.write(pos_arr.tobytes())
    f.write(freq_arr.tobytes())
    f.write(q.tobytes())
    f.write(scale.astype(np.float32).tobytes())
    f.write(neighbors.tobytes())

print("dim", DIM, "size MB", round(os.path.getsize(OUT) / 1e6, 2), "elapsed", round(time.time() - t0, 1))
for probe in ("casa", "água", "música", "ciência", "cérebro", "amor", "neurônio", "aprender", "entropia", "sinapse"):
    i = wid.get(probe)
    if i is None:
        print(probe, "MISSING")
        continue
    print(probe, "->", [words[k] for k in neighbors[i] if k >= 0][:6])
