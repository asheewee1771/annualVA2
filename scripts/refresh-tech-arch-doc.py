#!/usr/bin/env python3
"""Refreshes the generated parts of docs/tech-architecture.md from the repository, and its HTML copy.

The document is prose a person wrote and reviews, plus blocks this script owns. A block sits between
`<!-- generated:NAME ... -->` and `<!-- /generated:NAME -->`; everything outside the blocks is left alone.

    python scripts/refresh-tech-arch-doc.py            rewrite the blocks, then the HTML
    python scripts/refresh-tech-arch-doc.py --check    change nothing; exit 1 and name the blocks that are stale

What the blocks hold, and where each fact is read from:

    module-summary   the modules and their source-file counts          backend/src/main/java/sg/annualva/*
    module-graph     which module imports which                        the import statements
    module-catalog   endpoints and public types of every module        the controllers and the Javadoc
    adr-index        every decision record, its title and status       docs/adr/*.md
    er-diagram       every table, column and key                       the Flyway migrations, applied to a
    tables           every table, its size and its purpose             throwaway PostgreSQL in Docker

A module's or a table's description is kept as the document has it; a new one starts from the module's
package-info.java or from the comment above the table in its migration, and is then edited in the document.

The schema needs Docker (the same image as compose.yml). `--schema FILE` reads a saved dump instead, and
`--save-schema FILE` writes one. Sections 5 and 6 (dependencies, vulnerabilities) and all prose are not generated:
see "Keeping this document current" in the document. Python standard library only.
"""

import argparse
import datetime
import os
import re
import secrets
import subprocess
import sys
import tempfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, 'backend', 'src', 'main', 'java', 'sg', 'annualva')
ARCH = os.path.join(ROOT, 'backend', 'src', 'test', 'java', 'sg', 'annualva', 'arch')
MIGRATIONS = os.path.join(ROOT, 'backend', 'src', 'main', 'resources', 'db', 'migration')
ADRS = os.path.join(ROOT, 'docs', 'adr')
DOC = os.path.join(ROOT, 'docs', 'tech-architecture.md')
HTML = os.path.join(ROOT, 'docs', 'tech-architecture.html')
CONVERTER = os.path.join('.claude', 'skills', 'tech-arch-doc', 'md-to-html.py')
POSTGRES_IMAGE = 'postgres:17-alpine'
SELF = 'scripts/refresh-tech-arch-doc.py'

SCHEMA_SQL = r"""
\pset format unaligned
\pset tuples_only on
\pset fieldsep '|'
SELECT 'COL', c.table_name, c.column_name, c.data_type, c.udt_name, c.is_nullable, c.ordinal_position
FROM information_schema.columns c JOIN information_schema.tables t ON t.table_name = c.table_name AND t.table_schema = c.table_schema
WHERE c.table_schema = 'public' AND t.table_type = 'BASE TABLE' ORDER BY c.table_name, c.ordinal_position;
SELECT 'PK', tc.table_name, kcu.column_name FROM information_schema.table_constraints tc
JOIN information_schema.key_column_usage kcu ON kcu.constraint_name = tc.constraint_name AND kcu.table_schema = tc.table_schema
WHERE tc.table_schema = 'public' AND tc.constraint_type = 'PRIMARY KEY' ORDER BY tc.table_name, kcu.ordinal_position;
SELECT 'UK', tc.table_name, tc.constraint_name, string_agg(kcu.column_name, ',' ORDER BY kcu.ordinal_position)
FROM information_schema.table_constraints tc
JOIN information_schema.key_column_usage kcu ON kcu.constraint_name = tc.constraint_name AND kcu.table_schema = tc.table_schema
WHERE tc.table_schema = 'public' AND tc.constraint_type = 'UNIQUE' GROUP BY tc.table_name, tc.constraint_name ORDER BY 2, 3;
SELECT 'FK', conrelid::regclass, a.attname, confrelid::regclass FROM pg_constraint c
JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)
WHERE c.contype = 'f' AND c.connamespace = 'public'::regnamespace ORDER BY 2, 3;
"""


def read(path):
    with open(path, encoding='utf-8') as f:
        return f.read()


def fail(message):
    print('refresh-tech-arch-doc: ' + message, file=sys.stderr)
    sys.exit(2)


# ------------------------------------------------------------------------------------------------ Java sources

def plain(text):
    """Javadoc as plain text: inline tags and HTML removed, white space folded."""
    text = re.sub(r'\{@(?:link|linkplain)\s+([^}]*)\}', lambda m: m.group(1).split()[-1].split('.')[-1], text)
    text = re.sub(r'\{@code\s+([^}]*)\}', r'\1', text)
    text = re.sub(r'<[^>]+>', ' ', text)
    return re.sub(r'\s+', ' ', text).strip()


def first_sentence(text, limit=300):
    text = plain(text)
    match = re.match(r'(.+?[.!?])(\s|$)', text)
    sentence = match.group(1) if match else text
    if len(sentence) > limit:
        sentence = sentence[:limit].rsplit(' ', 1)[0] + ' ...'
    return sentence


def javadoc_text(comment):
    lines = [re.sub(r'^\s*\*\s?', '', line) for line in comment.splitlines()]
    return ' '.join(line for line in lines if not line.strip().startswith('@'))


ANNOTATION_AT_END = re.compile(r'@[\w.]+(?:\((?:[^()]|\((?:[^()]|\([^()]*\))*\))*\))?\s*$')


def javadoc_before(source, position):
    """The first sentence of the Javadoc that documents the type declared at position, past its annotations."""
    head = source[:position].rstrip()
    while True:
        without = ANNOTATION_AT_END.sub('', head).rstrip()
        if without == head:
            break
        head = without
    if not head.endswith('*/'):
        return ''
    start = head.rfind('/**')
    return first_sentence(javadoc_text(head[start + 3:-2])) if start >= 0 else ''


def module_sources():
    modules = {}
    for name in sorted(os.listdir(JAVA)):
        folder = os.path.join(JAVA, name)
        if os.path.isdir(folder):
            modules[name] = sorted(os.path.join(parent, f) for parent, _, files in os.walk(folder)
                                   for f in files if f.endswith('.java'))
    return modules


TYPE = re.compile(r'^public\s+(?:(?:final|abstract|sealed|non-sealed)\s+)*(class|record|interface|enum|@interface)\s+(\w+)',
                  re.M)
MAPPING = re.compile(r'@(Get|Post|Put|Delete|Patch)Mapping(\s*\(([^)]*)\))?')


def string_constants(modules):
    """Every `static final String` literal, by (declaring class, name): two classes may use the same constant name."""
    constants = {}
    for files in modules.values():
        for path in files:
            declaring = os.path.basename(path)[:-len('.java')]
            for match in re.finditer(r'static\s+final\s+String\s+(\w+)\s*=\s*"([^"]*)"\s*;', read(path)):
                constants[(declaring, match.group(1))] = match.group(2)
    return constants


def route_constant(constants, reference, declaring):
    """A route named by a constant: `Class.NAME` from that class, a bare `NAME` from the controller itself, else `<...>`."""
    parts = reference.split('.')
    owner, name = (parts[-2], parts[-1]) if len(parts) > 1 else (declaring, parts[0])
    return constants.get((owner, name), '<' + reference + '>')


def scan_modules(modules):
    """Public types, endpoints and imported modules of each module."""
    constants = string_constants(modules)
    types, endpoints, uses = {}, {}, {}
    for module, files in modules.items():
        types[module], endpoints[module], uses[module] = [], [], set()
        for path in files:
            source = read(path)
            declaring = os.path.basename(path)[:-len('.java')]
            for used in re.findall(r'^import\s+(?:static\s+)?sg\.annualva\.(\w+)\.', source, re.M):
                if used != module:
                    uses[module].add(used)
            if path.endswith('package-info.java'):
                continue
            for match in TYPE.finditer(source):
                kind = 'annotation' if match.group(1) == '@interface' else match.group(1)
                types[module].append((match.group(2), kind, javadoc_before(source, match.start())))
            if '@RestController' in source:
                base = re.search(r'@RequestMapping\(\s*(?:(?:value|path)\s*=\s*)?"([^"]*)"', source)
                for match in MAPPING.finditer(source):
                    arguments = match.group(3) or ''
                    literal = re.search(r'"([^"]*)"', arguments)
                    if literal:
                        route = literal.group(1)
                    else:
                        constant = re.search(r'([A-Za-z_][\w.]*)', arguments)
                        route = route_constant(constants, constant.group(1), declaring) if constant else ''
                    endpoints[module].append((match.group(1).upper(), (base.group(1) if base else '') + route))
        types[module].sort()
        endpoints[module].sort(key=lambda e: (e[1], e[0]))
    return types, endpoints, uses


def package_doc(module):
    path = os.path.join(JAVA, module, 'package-info.java')
    if not os.path.exists(path):
        return ''
    comment = re.search(r'/\*\*(.*?)\*/', read(path), re.S)
    return first_sentence(javadoc_text(comment.group(1))) if comment else ''


def module_groups(modules):
    """Platform and business modules, as ModuleConventionsArchTest.platformModulesDoNotDependOnBusinessModules lists them."""
    rule = re.search(r'void platformModulesDoNotDependOnBusinessModules\(\)(.*?)\n    \}',
                     read(os.path.join(ARCH, 'ModuleConventionsArchTest.java')), re.S)
    if not rule:
        fail('ModuleConventionsArchTest.platformModulesDoNotDependOnBusinessModules was not found')
    halves = rule.group(1).split('.should()')
    platform = re.findall(r'"sg\.annualva\.(\w+)\.\."', halves[0])
    business = re.findall(r'"sg\.annualva\.(\w+)\.\."', halves[1])
    others = [m for m in modules if m not in platform and m not in business and m not in ('app', 'adapters')]
    return platform, business, others


# ------------------------------------------------------------------------------------------------ ADRs, schema

def adr_index():
    rows = []
    for name in sorted(os.listdir(ADRS)):
        if not name.endswith('.md'):
            continue
        lines = read(os.path.join(ADRS, name)).splitlines()
        status = next((line.split(':', 1)[1] for line in lines[1:12] if line.lower().startswith('status:')), '')
        status = re.split(r'\.\s', re.sub(r'\s+', ' ', status).strip() + ' ')[0].strip().rstrip('.')
        rows.append((name[:4], name[:-3], lines[0].lstrip('# ').strip(), status))
    return rows


def migrations():
    found = []
    for name in os.listdir(MIGRATIONS):
        match = re.match(r'V(\d+)__.*\.sql$', name)
        if match:
            found.append((int(match.group(1)), name))
    return sorted(found)


def docker(*arguments, **options):
    return subprocess.run(['docker', *arguments], capture_output=True, text=True, encoding='utf-8', **options)


def dump_schema():
    """Applies every migration, in order, to a throwaway PostgreSQL and returns the dump of its public schema."""
    name = 'annualva-archdoc-' + secrets.token_hex(4)
    started = docker('run', '-d', '--rm', '--name', name, '-e', 'POSTGRES_PASSWORD=' + secrets.token_hex(16),
                     '-e', 'POSTGRES_DB=annualva', POSTGRES_IMAGE)
    if started.returncode != 0:
        fail('Docker could not start ' + POSTGRES_IMAGE + ' (is Docker running?): ' + started.stderr.strip()[:300])
    try:
        for _ in range(60):
            time.sleep(1)
            ready = docker('exec', name, 'psql', '-U', 'postgres', '-d', 'annualva', '-h', '127.0.0.1', '-tAc', 'SELECT 1')
            if ready.returncode == 0:
                break
        else:
            fail('PostgreSQL did not become ready in 60 seconds')
        psql = ['exec', '-i', name, 'psql', '-q', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'annualva']
        for _, migration in migrations():
            applied = docker(*psql, input=read(os.path.join(MIGRATIONS, migration)))
            if applied.returncode != 0:
                fail(migration + ' did not apply: ' + applied.stderr.strip()[:300])
        dumped = docker(*psql, input=SCHEMA_SQL)
        if dumped.returncode != 0:
            fail('the schema could not be read: ' + dumped.stderr.strip()[:300])
        return dumped.stdout
    finally:
        docker('rm', '-f', name)


def parse_schema(dump):
    columns, primary, foreign, unique = {}, {}, {}, {}
    for line in dump.splitlines():
        part = line.split('|')
        if part[0] == 'COL':
            columns.setdefault(part[1], []).append((part[2], part[4] if part[3] in ('USER-DEFINED', 'ARRAY') else part[3]))
        elif part[0] == 'PK':
            primary.setdefault(part[1], []).append(part[2])
        elif part[0] == 'FK':
            foreign.setdefault(part[1], []).append((part[2], part[3]))
        elif part[0] == 'UK':
            unique.setdefault(part[1], []).append(part[3].split(','))
    if not columns:
        fail('the schema dump names no table')
    return columns, primary, foreign, unique


def migration_comments():
    """What the migrations say each table is: `-- name: text` or, in a list of tables, `--   name  text`."""
    said = {}
    for _, migration in migrations():
        lines = read(os.path.join(MIGRATIONS, migration)).splitlines()
        for index, line in enumerate(lines):
            match = re.match(r'--\s*(\w+):\s+(\S.*)$', line) or re.match(r'--\s{2,}(\w+)\s{2,}(\S.*)$', line)
            if not match:
                continue
            text = [match.group(2)]
            for following in lines[index + 1:]:
                if not following.startswith('--') or re.match(r'--\s*\w+:(\s|$)', following) \
                        or re.match(r'--\s{2,}[\w, ]+?\s{2,}\S', following):
                    break
                text.append(following.lstrip('-').strip())
            said.setdefault(match.group(1), first_sentence(' '.join(text)))
    return said


# ------------------------------------------------------------------------------------------------ the blocks

def kept_descriptions(document, block):
    """The description cell of each row a block already has, by the name in its first cell."""
    body = re.search(r'<!-- generated:' + block + r'\b.*?-->\n(.*?)<!-- /generated:' + block + ' -->', document, re.S)
    kept = {}
    for row in (body.group(1).splitlines() if body else []):
        cells = [cell.strip() for cell in re.split(r'(?<!\\)\|', row)[1:-1]]
        if len(cells) >= 2 and cells[0].startswith('`') and cells[-1]:
            kept[cells[0].strip('`')] = cells[-1]
    return kept


def cell(text):
    return text.replace('|', '\\|')


class Facts:
    """What the blocks are made from, read once: the modules and what they hold, the ADRs and the schema."""

    def __init__(self, document, schema_dump):
        self.modules = module_sources()
        self.types, self.endpoints, self.uses = scan_modules(self.modules)
        self.platform, self.business, self.others = module_groups(self.modules)
        described = kept_descriptions(document, 'module-summary')
        self.purpose = {m: described.get(m) or cell(package_doc(m)) or 'TODO: describe this module.'
                        for m in self.modules}
        self.used_by = {m: sorted(o for o in self.modules if m in self.uses[o]) for m in self.modules}
        self.adrs = adr_index()
        self.columns, self.primary, self.foreign, self.unique = parse_schema(schema_dump)
        self.versions = [v for v, _ in migrations()]
        self.table_purposes = kept_descriptions(document, 'tables')
        self.migration_comments = migration_comments()


def module_summary(f):
    out = ['| Module | Source files | Description |', '|---|---|---|']
    out += [f'| `{m}` | {len(f.modules[m])} | {f.purpose[m]} |' for m in f.modules]
    out.append('| `frontend` | see section 1.8 | The React single-page application. |')
    out += ['', f'{len(f.modules)} back-end modules, {sum(len(files) for files in f.modules.values())} source files.']
    return out


def module_graph(f):
    out = ['```mermaid', 'graph TD', '  subgraph Frontend["Frontend (SPA)"]', '    FE[React screens by role]', '  end',
           '  subgraph Business["Business modules"]']
    out += [f'    {m}[{m}]' for m in f.business + f.others]
    out += ['  end', '  subgraph Platform["Platform modules"]']
    out += [f'    {m}[{m}]' for m in f.platform]
    out += ['  end', '  subgraph Adapters["Adapters (wired by Spring type only)"]']
    out.append('    adapters[adapters: ' + ', '.join(sorted(
        d for d in os.listdir(os.path.join(JAVA, 'adapters')) if os.path.isdir(os.path.join(JAVA, 'adapters', d)))) + ']')
    out += ['  end', '  FE -.->|REST API| web']
    out += [f'  {m} --> {used}' for m in f.modules if m not in ('app', 'adapters') for used in sorted(f.uses[m])]
    out += ['  adapters -.->|implements| boundary', '```', '',
            'Platform modules: ' + ', '.join(f'`{m}`' for m in f.platform) + '. Business modules: '
            + ', '.join(f'`{m}`' for m in f.business + f.others) + '. `adapters` uses '
            + ', '.join(f'`{m}`' for m in sorted(f.uses['adapters'])) + '; `app` uses '
            + ', '.join(f'`{m}`' for m in sorted(f.uses['app'])) + '.']
    return out


def module_catalog(f):
    out = []
    for m in f.modules:
        out += [f'#### `{m}`', f'- **Source files:** {len(f.modules[m])} under `backend/src/main/java/sg/annualva/{m}/`',
                f'- **Purpose:** {f.purpose[m]}',
                '- **Uses (imports):** ' + (', '.join(sorted(f.uses[m])) or 'no other module'),
                '- **Used by:** ' + (', '.join(f.used_by[m]) or 'no other module'), '']
        if f.endpoints[m]:
            out += ['**Endpoints:**', '', '| Method | Path |', '|---|---|']
            out += [f'| {method} | `{route or "/"}` |' for method, route in f.endpoints[m]]
            out.append('')
        if f.types[m]:
            out += ['| Public type | Kind | Description |', '|---|---|---|']
            out += [f'| `{name}` | {kind} | {cell(doc)} |' for name, kind, doc in f.types[m]]
            out.append('')
    return out[:-1]


def adr_index_block(f):
    out = [f'Every architecture decision record is under `docs/adr/` ({len(f.adrs)} in all). Each states the decision, '
           'why it was taken and what it does not do; those with alternatives name them in the record. The status is '
           'the first sentence of the record\'s status line.', '', '| ID | Title | Status | Record |', '|---|---|---|---|']
    out += [f'| ADR {number} | {cell(title)} | {cell(status)} | [{name}](adr/{name}.md) |'
            for number, name, title, status in f.adrs]
    return out


def er_diagram(f):
    out = [f'The schema is the result of applying Flyway migrations V{f.versions[0]} to V{f.versions[-1]} '
           f'({len(f.versions)} files) to an empty PostgreSQL 17 database: {len(f.columns)} tables, plus Flyway\'s own '
           'history table. Foreign keys are drawn parent to child.', '', '```mermaid', 'erDiagram']
    drawn = set()
    for table in sorted(f.foreign):
        for column, parent in f.foreign[table]:
            if (table, parent) not in drawn and table != parent:
                drawn.add((table, parent))
                out.append(f'    {parent} ||--o{{ {table} : "{column}"')
    for table in sorted(f.columns):
        out.append(f'    {table} {{')
        single_unique = {u[0] for u in f.unique.get(table, []) if len(u) == 1}
        references = {column for column, _ in f.foreign.get(table, [])}
        for column, kind in f.columns[table]:
            marks = [mark for mark, holds in (('PK', column in f.primary.get(table, [])), ('FK', column in references),
                                              ('UK', column in single_unique)) if holds]
            out.append(f'        {kind.replace(" ", "_")} {column}' + (' ' + ','.join(marks) if marks else ''))
        out.append('    }')
    out.append('```')
    return out


def tables(f):
    out = ['| Table | Columns | Primary key | Purpose |', '|---|---|---|---|']
    out += [f'| `{t}` | {len(f.columns[t])} | {", ".join(f.primary.get(t, []))} | '
            f'{f.table_purposes.get(t) or cell(f.migration_comments.get(t, "")) or "TODO: describe this table."} |'
            for t in sorted(f.columns)]
    return out


# Every generated block, by the name in its marker lines, and the function that writes its body. apply_blocks and
# --check go through this registry; a new block is a function here and two marker lines in the document.
BLOCKS = {
    'module-summary': module_summary,
    'module-graph': module_graph,
    'module-catalog': module_catalog,
    'adr-index': adr_index_block,
    'er-diagram': er_diagram,
    'tables': tables,
}


def build_blocks(document, schema_dump):
    facts = Facts(document, schema_dump)
    return {name: write(facts) for name, write in BLOCKS.items()}


def apply_blocks(document, blocks):
    stale = []
    for name, lines in blocks.items():
        pattern = re.compile(r'(<!-- generated:' + name + r'\b[^\n]*-->\n)(.*?)(<!-- /generated:' + name + ' -->)', re.S)
        found = pattern.search(document)
        if not found:
            fail(f'the document has no block "generated:{name}"; add the two marker lines where it belongs')
        body = '\n'.join(lines) + '\n'
        if found.group(2) != body:
            stale.append(name)
            document = document[:found.start(2)] + body + document[found.end(2):]
    return document, stale


# ------------------------------------------------------------------------------------------------ HTML

def find_converter(given):
    candidates = [given] if given else []
    candidates.append(os.path.join(ROOT, CONVERTER))
    common = subprocess.run(['git', '-C', ROOT, 'rev-parse', '--path-format=absolute', '--git-common-dir'],
                            capture_output=True, text=True)
    if common.returncode == 0:
        candidates.append(os.path.join(os.path.dirname(common.stdout.strip()), CONVERTER))
    return next((c for c in candidates if c and os.path.isfile(c)), None)


def write_html(document, converter):
    """The HTML copy, by the /tech-arch-doc converter, from the document without the marker lines."""
    visible = '\n'.join(line for line in document.split('\n') if not re.match(r'<!-- /?generated:', line))
    with tempfile.TemporaryDirectory() as folder:
        source = os.path.join(folder, 'tech-architecture.md')
        with open(source, 'w', encoding='utf-8', newline='\n') as f:
            f.write(visible)
        made = subprocess.run([sys.executable, converter, source, HTML], capture_output=True, text=True)
    if made.returncode != 0:
        fail('the HTML converter failed: ' + made.stderr.strip()[:300])
    with open(HTML, encoding='utf-8', newline='') as f:
        text = f.read()
    with open(HTML, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text.replace('\r\n', '\n'))


def main():
    parser = argparse.ArgumentParser(description='Refresh the generated blocks of docs/tech-architecture.md.')
    parser.add_argument('--check', action='store_true', help='change nothing; exit 1 if a block is stale')
    parser.add_argument('--schema', help='read the schema dump from this file instead of starting PostgreSQL in Docker')
    parser.add_argument('--save-schema', help='also write the schema dump to this file')
    parser.add_argument('--html-converter', help='path to md-to-html.py of the /tech-arch-doc skill')
    arguments = parser.parse_args()

    document = read(DOC)
    schema = read(arguments.schema) if arguments.schema else dump_schema()
    if arguments.save_schema:
        with open(arguments.save_schema, 'w', encoding='utf-8', newline='\n') as f:
            f.write(schema)
    refreshed, stale = apply_blocks(document, build_blocks(document, schema))
    todo = refreshed.count('TODO: describe this')

    if arguments.check:
        for name in stale:
            print(f'stale: generated:{name}')
        if todo:
            print(f'{todo} description(s) still say TODO')
        print('docs/tech-architecture.md is ' + ('STALE: run ' + SELF if stale else 'current with the code'))
        sys.exit(1 if stale or todo else 0)

    if stale:
        now = datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
        refreshed = re.sub(r'^> Generated: .*$', '> Generated: ' + now, refreshed, count=1, flags=re.M)
        with open(DOC, 'w', encoding='utf-8', newline='\n') as f:
            f.write(refreshed)
    print('blocks rewritten: ' + (', '.join(stale) or 'none (already current)'))
    if todo:
        print(f'{todo} new module or table description(s) say TODO: write them in the document')
    converter = find_converter(arguments.html_converter)
    if not converter:
        fail('docs/tech-architecture.html was NOT rewritten: ' + CONVERTER + ' was not found; pass --html-converter')
    write_html(refreshed, converter)
    print('docs/tech-architecture.html rewritten')


if __name__ == '__main__':
    main()
