#!/usr/bin/env python3
"""Render legal/*.md into self-contained, hostable HTML, and copy the source
Markdown into the app module's assets so it can also be rendered in-app.

Play Store/App Store require the privacy policy (and any other legal pages
linked from the listing) to live at a real URL, not just ship inside the
APK - the HTML output is what you'd upload to GitHub Pages or similar. The
in-app screens (see app/src/main/java/info/piepgras/gridracer/legal/) read
the copied Markdown directly from assets at runtime.

legal/*.md is the file to hand-edit; legal/*.html and the copies under
app/src/main/assets/legal/ are generated - re-run this script after any change:

    python3 scripts/render_legal_docs.py
"""
import html
import re
import shutil
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
LEGAL_DIR = REPO_ROOT / "legal"
ASSETS_DIR = REPO_ROOT / "app/src/main/assets/legal"
APP_NAME = "Grid Racer"

HTML_TEMPLATE = """<!doctype html>
<html lang="{lang}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title} - Grid Racer</title>
<style>
  body {{ font-family: -apple-system, Segoe UI, Roboto, sans-serif; max-width: 40rem;
         margin: 2rem auto; padding: 0 1rem; line-height: 1.5; color: #1a1a1a; }}
  h1 {{ font-size: 1.6rem; }}
  h2 {{ font-size: 1.15rem; margin-top: 2rem; }}
  h3 {{ font-size: 1rem; margin-top: 1.4rem; }}
  ul {{ padding-left: 1.3rem; }}
  code {{ background: #f5f5f5; padding: 0.1rem 0.3rem; border-radius: 3px; font-size: 0.9em; }}
  @media (prefers-color-scheme: dark) {{
    body {{ background: #16181c; color: #e6e6e6; }}
    code {{ background: #22252b; }}
    a {{ color: #7fb0ff; }}
  }}
</style>
</head>
<body>
{body}
</body>
</html>
"""


def inline(text: str) -> str:
    """Inline Markdown: code, links, bold, italics. Everything else is escaped."""
    out = html.escape(text)
    out = re.sub(r"`([^`]+)`", r"<code>\1</code>", out)
    out = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", _link, out)
    out = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", out)
    out = re.sub(r"(?<!\*)\*([^*\n]+)\*(?!\*)", r"<em>\1</em>", out)
    out = re.sub(r"(?<![\"'>=])\bhttps?://[^\s<>\"]+", _bare_url, out)
    out = re.sub(r"(?<![\">:/])\b([\w.+-]+@[\w-]+\.[\w.]+)\b",
                 r'<a href="mailto:\1">\1</a>', out)
    return out


def _link(match: re.Match) -> str:
    label, target = match.group(1), match.group(2)
    if target.endswith(".md"):
        target = Path(target).stem + ".html"
    external = ' target="_blank" rel="noopener"' if target.startswith("http") else ""
    return f'<a href="{target}"{external}>{label}</a>'


def _bare_url(match: re.Match) -> str:
    url = match.group(0).rstrip(".,;:)")
    trailing = match.group(0)[len(url):]
    return f'<a href="{url}" target="_blank" rel="noopener">{url}</a>{trailing}'


def markdown_to_html(text: str) -> str:
    """Convert Markdown to HTML. Handles headings, paragraphs, lists, and
    inline formatting."""
    lines = text.splitlines()
    out: list[str] = []
    i = 0
    open_list: str | None = None

    def close_list() -> None:
        nonlocal open_list
        if open_list:
            out.append(f"</{open_list}>")
            open_list = None

    while i < len(lines):
        line = lines[i]
        stripped = line.strip()

        if not stripped:
            close_list()
            i += 1
            continue

        heading = re.match(r"^(#{1,4})\s+(.*)$", stripped)
        if heading:
            close_list()
            level = len(heading.group(1))
            tag = f"h{level}"
            out.append(f"<{tag}>{inline(heading.group(2))}</{tag}>")
            i += 1
            continue

        bullet = re.match(r"^\s*([-*]|\d+\.)\s+(.*)$", line)
        if bullet:
            kind = "ul" if bullet.group(1) in ("-", "*") else "ol"
            if open_list != kind:
                close_list()
                out.append(f"<{kind}>")
                open_list = kind
            item = [bullet.group(2)]
            i += 1
            while i < len(lines) and lines[i].startswith(("  ", "\t")) and lines[i].strip():
                item.append(lines[i].strip())
                i += 1
            out.append(f"<li>{inline(' '.join(item))}</li>")
            continue

        close_list()
        para = [stripped]
        i += 1
        while i < len(lines) and lines[i].strip() and not re.match(
                r"^\s*([-*>|#]|\d+\.)\s", lines[i]):
            para.append(lines[i].strip())
            i += 1
        out.append(f"<p>{inline(' '.join(para))}</p>")

    close_list()
    return "\n".join(out)


def main() -> None:
    md_files = sorted(LEGAL_DIR.glob("*.md"))
    if not md_files:
        raise SystemExit(f"No .md files found under {LEGAL_DIR}")

    ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    for md_path in md_files:
        lang = "de" if md_path.name.endswith(".de.md") else "en"
        text = md_path.read_text()
        title_match = re.search(r"^#\s+(.*)$", text, re.M)
        title = title_match.group(1).strip() if title_match else md_path.stem
        body = markdown_to_html(text)
        html_out = HTML_TEMPLATE.format(
            title=html.escape(title),
            body=body,
            lang=lang,
        )
        out_path = md_path.with_suffix(".html")
        out_path.write_text(html_out)
        print(f"Wrote {out_path}")

        asset_path = ASSETS_DIR / md_path.name
        shutil.copyfile(md_path, asset_path)
        print(f"Copied to {asset_path}")


if __name__ == "__main__":
    main()
