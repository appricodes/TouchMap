#!/usr/bin/env python3
"""Insert missing <string> resources into app/src/main/res/values-*/strings.xml.

Written for the one-off backfill of the Credits screen and the About/Impressum screen, whose
strings were added after the 47-language localization pass and so existed only in values/. Kept in
the tree because the same situation recurs every time a screen is added between localization runs.

Input: one or more JSON files shaped {"<locale-dir-suffix>": {"<key>": "<translated text>"}} with
UNESCAPED text -- this script does the Android XML escaping, so the JSON stays readable.

Guarantees, all of which abort the run rather than write a bad file:
  * a translation must carry exactly the same multiset of printf format specifiers as its English
    source. Dropping or renumbering %1$d in a translation compiles fine and throws
    IllegalFormatException at runtime, which is the failure mode this whole script exists to avoid.
  * keys marked translatable="false" in values/strings.xml are refused (that is what lint's
    ExtraTranslation flags, and for the About screen those keys hold a postal address, not prose).
  * a key already present in a locale is left alone, so re-running is a no-op.
"""
import argparse
import io
import json
import os
import re
import sys

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'src', 'main', 'res')
FORMAT_RE = re.compile(r'%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]')
STRING_RE = re.compile(r'<string name="([^"]+)"([^>]*)>(.*?)</string>', re.S)


def read_strings(path):
    """Return {key: (attrs, raw_value)} for one strings.xml."""
    src = io.open(path, encoding='utf-8').read()
    return {m.group(1): (m.group(2), m.group(3)) for m in STRING_RE.finditer(src)}


def escape(text):
    """Plain text -> Android string-resource body."""
    text = text.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
    text = text.replace("'", "\\'").replace('"', '\\"')
    if text[:1] in ('@', '?'):
        text = '\\' + text
    return text


def specifiers(text):
    return sorted(FORMAT_RE.findall(text))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('json_files', nargs='+')
    ap.add_argument('--dry-run', action='store_true')
    args = ap.parse_args()

    base = read_strings(os.path.join(RES, 'values', 'strings.xml'))

    merged = {}
    for jf in args.json_files:
        for loc, pairs in json.load(io.open(jf, encoding='utf-8')).items():
            merged.setdefault(loc, {}).update(pairs)

    errors, written, skipped = [], 0, 0
    for loc in sorted(merged):
        path = os.path.join(RES, 'values-' + loc, 'strings.xml')
        if not os.path.exists(path):
            errors.append('no such locale dir: values-%s' % loc)
            continue
        existing = read_strings(path)
        additions = []
        for key, value in merged[loc].items():
            if key not in base:
                errors.append('%s: key not in values/strings.xml: %s' % (loc, key))
                continue
            attrs, english = base[key]
            if 'translatable="false"' in attrs:
                errors.append('%s: %s is translatable="false"' % (loc, key))
                continue
            if key in existing:
                skipped += 1
                continue
            got, want = specifiers(escape(value)), specifiers(english)
            if got != want:
                errors.append('%s/%s: format specifiers %s != English %s' % (loc, key, got, want))
                continue
            additions.append((key, value))
        if not additions:
            continue
        src = io.open(path, encoding='utf-8').read()
        block = ''.join('    <string name="%s">%s</string>\n' % (k, escape(v))
                        for k, v in additions)
        assert '</resources>' in src, path
        src = src.replace('</resources>', block + '</resources>', 1)
        if not args.dry_run:
            io.open(path, 'w', encoding='utf-8', newline='\n').write(src)
        written += len(additions)

    for e in errors:
        print('ERROR ' + e, file=sys.stderr)
    print('%s: %d strings into %d locales (%d already present)'
          % ('would write' if args.dry_run else 'wrote', written, len(merged), skipped))
    return 1 if errors else 0


if __name__ == '__main__':
    sys.exit(main())
