"""Filter the kaikki Portuguese dump down to the words people actually use."""
import gzip, json, os, re, sys, time

DEST = r"C:\Users\conta\OneDrive\Documentos\deepseek-harness\default-workspace\tools\data"
SRC = os.path.join(DEST, "kaikki_pt.jsonl.gz")
FREQ = os.path.join(DEST, "pt_50k.txt")
OUT = os.path.join(DEST, "dictionary.jsonl")

MAX_WORD_LEN = 32
WORD_RE = re.compile(r'"word":\s*"([^"]{1,%d})"' % MAX_WORD_LEN)

freq = {}
with open(FREQ, encoding="utf-8") as f:
    for i, line in enumerate(f):
        parts = line.rsplit(" ", 1)
        if len(parts) != 2:
            continue
        w, c = parts
        try:
            freq[w.strip().lower()] = int(c)
        except ValueError:
            continue
print("freq words", len(freq))

t0 = time.time()
seen = set()
kept = 0
by_pos = {}
with gzip.open(SRC, "rt", encoding="utf-8") as f, open(OUT, "w", encoding="utf-8") as out:
    for n, line in enumerate(f):
        if n % 500000 == 0 and n:
            print("  lines", n, "kept", kept, "t", round(time.time() - t0, 1), flush=True)
        m = WORD_RE.search(line)
        if not m:
            continue
        word = m.group(1).strip().lower()
        if word not in freq:
            continue
        if (word, ) in seen:
            # keep at most one entry per (word, pos) below
            pass
        try:
            obj = json.loads(line)
        except Exception:
            continue
        pos = obj.get("pos") or ""
        key = (word, pos)
        if key in seen:
            continue
        senses = obj.get("senses") or []
        glosses = []
        for s in senses[:3]:
            for g in (s.get("glosses") or [])[:2]:
                g = g.strip()
                if 3 < len(g) < 260:
                    glosses.append(g)
            if len(glosses) >= 3:
                break
        if not glosses:
            continue
        syn = [x.get("word") for x in (obj.get("synonyms") or []) if x.get("word")][:6]
        hyp = [x.get("word") for x in (obj.get("hypernyms") or []) if x.get("word")][:4]
        holo = [x.get("word") for x in (obj.get("holonyms") or []) if x.get("word")][:3]
        rec = {
            "w": word,
            "p": pos,
            "d": glosses[:3],
            "s": syn,
            "h": hyp + holo,
            "f": freq.get(word, 0),
        }
        out.write(json.dumps(rec, ensure_ascii=False) + "\n")
        seen.add(key)
        kept += 1
        by_pos[pos] = by_pos.get(pos, 0) + 1

print("entries", kept, "words", len({k[0] for k in seen}), "pos", by_pos, "t", round(time.time() - t0, 1))
print("size", os.path.getsize(OUT))
