"""Turns OpenStax CNXML modules into plain text, one file per module.

    python tools/cnxml_to_text.py OUT_DIR MODULE.cnxml [MODULE.cnxml ...]

Keeps the title, section titles, paragraphs and list items; drops figures,
media, glossary and review questions, so what's left is the prose a reader
extracts facts from. Used by the extraction check workflow.
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

NS = "{http://cnx.rice.edu/cnxml}"
SKIP = {"figure", "media", "glossary", "exercise", "note", "table", "equation"}


def text_of(el):
    return re.sub(r"\s+", " ", "".join(el.itertext())).strip()


def walk(el, out):
    tag = el.tag.replace(NS, "")
    if tag in SKIP:
        return
    if tag == "title":
        t = text_of(el)
        if t:
            out.append("# " + t)
        return
    if tag == "para":
        t = text_of(el)
        if t:
            out.append(t)
        return
    if tag == "item":
        t = text_of(el)
        if t:
            out.append("- " + t)
        return
    for c in el:
        walk(c, out)


def main():
    out_dir, files = sys.argv[1], sys.argv[2:]
    os.makedirs(out_dir, exist_ok=True)
    for f in files:
        root = ET.parse(f).getroot()
        out = []
        title = root.find(NS + "title")
        if title is not None:
            out.append("# " + text_of(title))
        walk(root.find(NS + "content"), out)
        name = os.path.basename(os.path.dirname(f)) + ".md"
        with open(os.path.join(out_dir, name), "w", encoding="utf-8") as fh:
            fh.write("\n\n".join(out) + "\n")


if __name__ == "__main__":
    main()
