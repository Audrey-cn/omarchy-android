"""Exercise the actual launcher block and release branch without host/app writes."""
import ast
import contextlib
import io
import json
import os
from pathlib import Path
import socket
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[1]


class CcSwitchProxyTests(unittest.TestCase):
    def run_block(self, db, proxy_up, process_env=None):
        text = (REPO / 'core/ai-suite/cc-switch-launch').read_text()
        block = text.split('python3 - << "PY"\n', 1)[1].split('\nPY\n', 1)[0]
        tree = ast.parse(block)

        class RedirectFixtureDatabase(ast.NodeTransformer):
            def visit_Constant(self, node):
                if node.value == '/root/.cc-switch/cc-switch.db':
                    return ast.copy_location(ast.Constant(str(db)), node)
                return node

        tree = ast.fix_missing_locations(RedirectFixtureDatabase().visit(tree))

        class ProxySocket:
            def settimeout(self, value): pass
            def connect(self, address):
                if not proxy_up:
                    raise OSError('fixture: no proxy')
            def close(self): pass

        env = {'no_proxy': '', 'NO_PROXY': '', **(process_env or {})}
        with patch.dict(os.environ, env), patch.object(socket, 'socket', ProxySocket), contextlib.redirect_stderr(io.StringIO()):
            exec(compile(tree, '<actual CC Switch launcher block>', 'exec'), {'__name__': '__main__'})

    def test_first_launch_does_not_create_database_or_parent(self):
        with tempfile.TemporaryDirectory() as temp:
            db = Path(temp) / '.cc-switch/cc-switch.db'
            self.run_block(db, True)
            self.assertFalse(db.parent.exists())

    def test_existing_directory_without_database_is_left_for_app_initialization(self):
        with tempfile.TemporaryDirectory() as temp:
            db = Path(temp) / '.cc-switch/cc-switch.db'
            db.parent.mkdir()
            self.run_block(db, False)
            self.assertFalse(db.exists())

    def test_existing_database_without_settings_schema_is_unchanged(self):
        with tempfile.TemporaryDirectory() as temp:
            db = Path(temp) / 'cc-switch.db'
            with sqlite3.connect(db) as conn:
                conn.execute('CREATE TABLE application_owned(value TEXT)')
            before = db.read_bytes()
            self.run_block(db, True)
            self.assertEqual(db.read_bytes(), before)

    def test_existing_settings_insert_and_delete_work_and_preserve_other_keys(self):
        with tempfile.TemporaryDirectory() as temp:
            db = Path(temp) / 'cc-switch.db'
            with sqlite3.connect(db) as conn:
                conn.execute('CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT)')
                conn.execute('INSERT INTO settings VALUES(?, ?)', ('other', 'keep'))
            self.run_block(db, True)
            with sqlite3.connect(db) as conn:
                self.assertEqual(conn.execute('SELECT value FROM settings WHERE key=?', ('global_proxy_url',)).fetchone(),
                                 ('http://127.0.0.1:18888',))
            self.run_block(db, False)
            with sqlite3.connect(db) as conn:
                self.assertEqual(conn.execute('SELECT key,value FROM settings').fetchall(), [('other', 'keep')])

    def test_explicit_process_no_proxy_star_overrides_a_listening_proxy(self):
        for key, value in (('no_proxy', '*'), ('NO_PROXY', '*'), ('NO_PROXY', 'localhost, *,127.0.0.1')):
            with self.subTest(key=key, value=value), tempfile.TemporaryDirectory() as temp:
                db = Path(temp) / 'cc-switch.db'
                with sqlite3.connect(db) as conn:
                    conn.execute('CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT)')
                    conn.executemany('INSERT INTO settings VALUES(?,?)', [
                        ('global_proxy_url', 'http://127.0.0.1:18888'), ('other', 'keep'),
                    ])
                self.run_block(db, True, {key: value})
                with sqlite3.connect(db) as conn:
                    self.assertEqual(conn.execute('SELECT key,value FROM settings').fetchall(), [('other', 'keep')])


class CcSwitchReleaseTests(unittest.TestCase):
    def run_branch(self, effective_url, curl_status=0):
        text = (REPO / 'core/bin/omarchy-installer.sh').read_text()
        body = text.split('    install.ai.cc-switch)\n', 1)[1].split('\n        ;;', 1)[0]
        with tempfile.TemporaryDirectory(prefix='cc-release-fixture-') as temp:
            root = Path(temp)
            commands = root / 'bin'
            commands.mkdir()
            apps = root / 'apps'
            apps.mkdir()
            calls = root / 'curl.jsonl'
            apt = root / 'apt-called'
            curl = commands / 'curl'
            curl.write_text('#!' + sys.executable + '''
import json,os,sys
with open(os.environ['TEST_CALLS'], 'a') as out: out.write(json.dumps(sys.argv[1:])+'\\n')
if any(arg.endswith('/releases/latest') for arg in sys.argv[1:]):
    sys.stdout.write(os.environ['TEST_EFFECTIVE_URL'])
    sys.exit(int(os.environ['TEST_CURL_STATUS']))
if '-o' in sys.argv:
    open(sys.argv[sys.argv.index('-o')+1], 'wb').write(b'fixture-deb')
''')
            curl.chmod(0o755)
            for name, script in {
                'apt-get': '#!/bin/sh\nprintf called > "$TEST_APT"\n',
                'update-desktop-database': '#!/bin/sh\nexit 0\n',
            }.items():
                command = commands / name
                command.write_text(script)
                command.chmod(0o755)
            body = body.replace('/tmp/cc-switch_arm64.deb', str(root / 'cc-switch_arm64.deb'))
            script = 'set -e\nensure_apt_updated() { :; }\nAPP_DIR="$TEST_APPS"\n' + body
            env = dict(os.environ, PATH=str(commands)+':'+os.environ['PATH'], TEST_CALLS=str(calls),
                       TEST_EFFECTIVE_URL=effective_url, TEST_CURL_STATUS=str(curl_status),
                       TEST_APT=str(apt), TEST_APPS=str(apps))
            result = subprocess.run(['bash', '-c', script], env=env, capture_output=True, text=True)
            return result, [json.loads(line) for line in calls.read_text().splitlines()], apt.exists()

    def test_failed_release_resolution_does_not_download_or_install(self):
        result, calls, apt = self.run_branch('https://github.com/farion1231/cc-switch/releases/latest', 56)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(calls), 1)
        self.assertFalse(apt)
        self.assertNotIn('✓', result.stdout)

    def test_invalid_or_unresolved_version_does_not_download_or_install(self):
        for url in ('https://github.com/farion1231/cc-switch/releases/latest',
                    'https://github.com/farion1231/cc-switch/releases/tag/vnot-a-version'):
            with self.subTest(url=url):
                result, calls, apt = self.run_branch(url)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(len(calls), 1)
                self.assertFalse(apt)

    def test_expected_release_tag_produces_expected_arm64_asset_url(self):
        result, calls, apt = self.run_branch('https://github.com/farion1231/cc-switch/releases/tag/v3.15.0')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(apt)
        self.assertEqual(calls[1][-1], 'https://github.com/farion1231/cc-switch/releases/download/v3.15.0/CC-Switch-v3.15.0-Linux-arm64.deb')


if __name__ == '__main__':
    unittest.main()
