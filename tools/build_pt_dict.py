"""Extract Portuguese definitions from the pt.wiktionary dump (linear-time parser)."""
import bz2, os, re, time

DEST = r"C:\Users\conta\OneDrive\Documentos\deepseek-harness\default-workspace\tools\data"
GZ = os.path.join(DEST, "ptwiktionary.xml.bz2")
OUT = os.path.join(DEST, "pt_defs.tsv")
FREQ = os.path.join(DEST, "pt_50k.txt")

freq = set()
for line in open(FREQ, encoding="utf-8"):
    parts = line.rsplit(" ", 1)
    if len(parts) == 2:
        freq.add(parts[0].strip().lower())
print("freq words", len(freq), flush=True)

TEMPLATE = re.compile(r"\{\{[^{}]*\}\}")
LINK = re.compile(r"\[\[(?:[^\]|]*\|)?([^\]]*)\]\]")
TAG = re.compile(r"<[^>]{1,80}>")
REF = re.compile(r"<ref[^>]*>.*?</ref>", re.S)
BOLD = re.compile(r"'{2,5}")
TITLE_RE = re.compile(r"<title>(.*?)</title>", re.S)
TEXT_RE = re.compile(r"<text[^>]*>(.*?)</text>", re.S)
LANG_PT = re.compile(r"(?m)^\s*={1,4}\s*\{\{-pt-\}\}\s*={1,4}\s*$")
LANG_ANY = re.compile(r"(?m)^\s*={1,4}\s*\{\{-[a-z]{2,3}-\}\}\s*={1,4}\s*$")
PT_WORD = re.compile(r"(?m)^\s*==+\s*[Pp]ortugu[êe]s\s*==+\s*$")
HEADER = re.compile(r"^=+\s*(.*?)\s*=+\s*$", re.M)
ENTITY = re.compile(r"&(amp|quot|lt|gt|nbsp);")


def clean(text):
    text = REF.sub("", text)
    for _ in range(3):
        text = TEMPLATE.sub("", text)
    text = LINK.sub(r"\1", text)
    text = BOLD.sub("", text)
    text = TAG.sub("", text)
    text = ENTITY.sub(" ", text)
    text = re.sub(r"\s+", " ", text)
    return text.strip(" ;:.,\u00a0")


t0 = time.time()
written = 0
pages = 0
carry = ""
with bz2.open(GZ, "rt", encoding="utf-8", errors="replace") as f, open(OUT, "w", encoding="utf-8") as out:
    while True:
        chunk = f.read(1 << 22)
        if not chunk:
            break
        data = carry + chunk
        parts = data.split("<page>")
        carry = parts.pop()
        for page in parts:
            end = page.find("</page>")
            if end < 0:
                continue
            page = page[:end]
            pages += 1
            tm = TITLE_RE.search(page)
            if not tm:
                continue
            title = tm.group(1).strip().lower()
            if not title or len(title) > 32 or " " in title or title not in freq:
                continue
            xm = TEXT_RE.search(page)
            if not xm:
                continue
            body = xm.group(1)
            pt = LANG_PT.search(body) or PT_WORD.search(body)
            if pt is not None:
                tail = body[pt.end():]
                nxt = LANG_ANY.search(tail)
                section = tail[:nxt.start()] if nxt else tail
            elif LANG_ANY.search(body) is None:
                section = body
            else:
                continue
            defs = []
            for line in section.split("\n"):
                s = line.strip()
                if not s.startswith("#"):
                    continue
                s = s.lstrip("#").strip()
                if not s:
                    continue
                c = clean(s)
                if 3 < len(c) < 300 and not c.startswith("("):
                    defs.append(c)
                if len(defs) >= 3:
                    break
            if defs:
                out.write(title + "\t" + " || ".join(defs) + "\n")
                written += 1
        if pages % 40000 < 5000:
            out.flush()
            print("  pages", pages, "written", written, "t", round(time.time() - t0, 1), flush=True)

print("pages", pages, "written", written, "elapsed", round(time.time() - t0, 1))
print("size", os.path.getsize(OUT))
