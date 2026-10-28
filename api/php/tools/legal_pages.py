"""Public pages of the legal texts: docs/legal/*.md → api/php/public/legal/*.html.

    pip install markdown==3.7      (BSD-3-Clause)
    python api/php/tools/legal_pages.py

- Links between the legal texts are resolved relative to each page (so
  "English version" ↔ "Spanish original" work); other links into the repo are
  dropped and keep their text (the repo is private). Anchors as on GitHub.
- Internal notes are removed ("; ver README.md", "Se publica en …").
- "- [ ]" becomes "☐". Minimal inline CSS with dark mode, no cookies, no scripts.
The generated HTML is versioned and goes into the production package.
"""

from __future__ import annotations

import posixpath
import re
import sys
from pathlib import Path

import markdown
from markdown.extensions.toc import slugify_unicode

ROOT = Path(__file__).resolve().parents[3]
SRC = ROOT / "docs" / "legal"
OUT = ROOT / "api" / "php" / "public" / "legal"

# source (relative to docs/legal) → page (relative to public/legal), language
PAGES = {
    "aviso-de-privacidad.md": ("privacidad.html", "es"),
    "consentimiento-uso-de-video.md": ("consentimiento.html", "es"),
    "formato-consentimiento-jugadores.md": ("formato-jugadores.html", "es"),
    "en/privacy-notice.md": ("en/privacy.html", "en"),
    "en/video-consent.md": ("en/consent.html", "en"),
    "en/players-consent-form.md": ("en/players-form.html", "en"),
}

CSS = """
:root{color-scheme:light dark;--bg:#F0F2F5;--card:#FFFFFF;--text:#080809;--muted:#65676B;--link:#0866FF;--line:#CED0D4}
@media (prefers-color-scheme:dark){:root{--bg:#18191A;--card:#242526;--text:#E4E6EB;--muted:#B0B3B8;--link:#4599FF;--line:#3E4042}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:16px/1.55 Roboto,system-ui,-apple-system,"Segoe UI",sans-serif}
main{max-width:760px;margin:0 auto;padding:16px}article{background:var(--card);border-radius:12px;padding:20px 18px}
h1{font-size:24px;line-height:30px;margin:0 0 12px}h2{font-size:20px;margin:28px 0 8px}h3{font-size:17px;margin:20px 0 6px}
a{color:var(--link)}blockquote{margin:0 0 16px;padding:8px 12px;border-left:3px solid var(--line);color:var(--muted)}
table{border-collapse:collapse;width:100%;display:block;overflow-x:auto}th,td{border:1px solid var(--line);padding:6px 8px;text-align:left;vertical-align:top}
code{font-size:14px}hr{border:0;border-top:1px solid var(--line);margin:24px 0}li{margin:4px 0}footer{color:var(--muted);font-size:13px;padding:16px 4px}
"""

INTERNAL_NOTES = [
    re.compile(r";\s*ver \[README\.md\]\(README\.md\)"),
    re.compile(r";\s*see \[README\.md\]\(README\.md\)"),
    re.compile(r"\s*Se publica en `[^`]+`\."),
    re.compile(r"\s*Published at `[^`]+`\."),
]
LINK = re.compile(r"(?<!!)\[([^\]]+)\]\(([^)\s]+)\)")


def rewrite_links(text: str, source: str) -> str:
    here = posixpath.dirname(source)
    page_dir = posixpath.dirname(PAGES[source][0])

    def repl(m: re.Match) -> str:
        label, target = m.group(1), m.group(2)
        if target.startswith(("http://", "https://", "mailto:", "#")):
            return m.group(0)
        path, _, anchor = target.partition("#")
        resolved = posixpath.normpath(posixpath.join(here, path))
        if resolved in PAGES:
            href = posixpath.relpath(PAGES[resolved][0], page_dir or ".")
            return f"[{label}]({href}{'#' + anchor if anchor else ''})"
        return label  # a link into the private repo: keep only the text

    return LINK.sub(repl, text)


def render(source: str) -> str:
    text = (SRC / source).read_text(encoding="utf-8")
    for pattern in INTERNAL_NOTES:
        text = pattern.sub("", text)
    text = re.sub(r"^(\s*)- \[ \] ", r"\1- ☐ ", text, flags=re.M)
    text = re.sub(r"^(\s*)- \[x\] ", r"\1- ☑ ", text, flags=re.M)
    text = rewrite_links(text, source)
    md = markdown.Markdown(extensions=["tables", "toc", "sane_lists"], extension_configs={"toc": {"slugify": slugify_unicode}})
    body = md.convert(text)
    page, lang = PAGES[source]
    title_match = re.search(r"^# (.+)$", text, re.M)
    title = title_match.group(1).strip() if title_match else "PP-Coach-Tracker"
    footer = "PP-Coach-Tracker · Borrador, no es asesoría legal." if lang == "es" else "PP-Coach-Tracker · Draft, not legal advice."
    return (
        f'<!doctype html>\n<html lang="{lang}">\n<head>\n<meta charset="utf-8">\n'
        f'<meta name="viewport" content="width=device-width, initial-scale=1">\n'
        f"<title>{title}</title>\n<style>{CSS.strip()}</style>\n</head>\n<body>\n<main>\n<article>\n{body}\n</article>\n"
        f"<footer>{footer}</footer>\n</main>\n</body>\n</html>\n"
    )


def main() -> int:
    for source, (page, _) in PAGES.items():
        out = OUT / page
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(render(source), encoding="utf-8")
        print(f"{source} → {out.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
