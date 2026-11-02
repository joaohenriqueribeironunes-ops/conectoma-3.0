"""Turn the raw dictionary into a lemma-aware lexicon."""
import json, re, os, collections

DEST = r"C:\Users\conta\OneDrive\Documentos\deepseek-harness\default-workspace\tools\data"
SRC = os.path.join(DEST, "dictionary.jsonl")
OUT = os.path.join(DEST, "lexicon.tsv")

FORM_PATTERNS = [
    r"^(?:feminine|masculine)\s+(?:singular|plural)\s+of\s+(.+)$",
    r"^(?:first|second|third)-person\s+(?:singular|plural)\s+.+?\s+of\s+(.+)$",
    r"^(?:present|past|future)\s+(?:participle|indicative|subjunctive|imperative)\b.*?\sof\s+(.+)$",
    r"^(?:gerund|plural|singular|inflection|preterite|imperfect)\b.*?\sof\s+(.+)$",
    r"^(?:alternative|obsolete|archaic|misspelling|eye dialect|nonstandard|superseded)\s+(?:form|spelling)\s+of\s+(.+)$",
    r"^(?:form|variant|synonym)\s+of\s+(.+)$",
    r"^diminutive of\s+(.+)$",
    r"^augmentative of\s+(.+)$",
]
FORM_RE = [re.compile(p, re.I) for p in FORM_PATTERNS]
CLEAN = re.compile(r"\s+")


def clean(text):
    text = text.replace("{{", "").replace("}}", "")
    text = re.sub(r"\[\[([^\]|]*\|)?([^\]]*)\]\]", r"\2", text)
    return CLEAN.sub(" ", text).strip()


def lemma_of(gloss):
    g = clean(gloss)
    for rx in FORM_RE:
        m = rx.match(g)
        if m:
            lem = m.group(1).strip().strip(".,;").lower()
            lem = lem.split(",")[0].strip()
            if 1 < len(lem) < 32 and " " not in lem:
                return lem
    return None


POS_PRIORITY = {"noun": 0, "verb": 0, "adj": 1, "adv": 1, "name": 2, "pron": 3, "num": 3,
                "prep": 4, "conj": 4, "intj": 4, "article": 5, "det": 5, "particle": 5,
                "contraction": 6, "phrase": 6, "character": 7, "symbol": 7}

rows = [json.loads(l) for l in open(SRC, encoding="utf-8")]
by_word = collections.defaultdict(list)
for r in rows:
    by_word[r["w"]].append(r)

out = []
stats = collections.Counter()
for word, entries in by_word.items():
    entries.sort(key=lambda r: POS_PRIORITY.get(r["p"], 9))
    real_def = None
    lemma = None
    syns = []
    for r in entries:
        for g in r["d"]:
            g2 = clean(g)
            if not g2:
                continue
            lem = lemma_of(g2)
            if lem and lem != word:
                if lemma is None:
                    lemma = lem
                stats["form_of"] += 1
            elif real_def is None and len(g2) > 3:
                real_def = g2
                stats["real_def"] += 1
                break
        syns.extend([s for s in r["s"] if s and s.lower() != word])
        if real_def and lemma:
            break
    if real_def is None and lemma is None:
        continue
    # the word itself may be a lemma referenced by inflections
    definition = real_def if real_def else ""
    if not definition and lemma:
        definition = "forma flexionada de %s" % lemma
    freq = max(r["f"] for r in entries)
    pos = entries[0]["p"] if entries else ""
    out.append((word, lemma or "", pos, definition, freq, ",".join(dict.fromkeys(syns[:4]))))

out.sort(key=lambda x: (-x[4], x[0]))
with open(OUT, "w", encoding="utf-8", newline="\n") as f:
    for w, lem, pos, d, fr, syn in out:
        f.write("%s\t%s\t%s\t%s\t%d\t%s\n" % (w, lem, pos, d, fr, syn))

print("words", len(out), "with real def", sum(1 for x in out if not x[3].startswith("forma flexionada")),
      "lemmas", len({x[1] for x in out if x[1]}), "stats", dict(stats))
print("size", os.path.getsize(OUT))
for row in out[:12]:
    print(row[0], "|", row[2], "|", row[3][:90])
