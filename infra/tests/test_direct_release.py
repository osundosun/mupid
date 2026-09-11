"""Offline behavioral checks for direct release; no SSH, Docker daemon, or root.

Run with MUPID_TEST_BASH pointing to Git Bash on Windows, or bash on Linux.
"""
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
BASH = os.environ.get('MUPID_TEST_BASH') or shutil.which('bash')
SHA = 'a' * 40


class DirectReleaseTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.cwd = Path(self.tmp.name)

    def write(self, name, source):
        path = self.cwd / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source, encoding='utf-8', newline='\n')
        return path

    def run_script(self, script, *args, **variables):
        env = dict(os.environ, **variables)
        return subprocess.run([BASH, str(script), *args], cwd=self.cwd,
                              env=env, capture_output=True, text=True, encoding='utf-8', timeout=15)

    def test_only_absent_release_builds_and_uploads(self):
        self.write('infra/ci/deploy.sh',
                   'echo "$1" >> calls\n'
                   '[ "$1" != resume ] || exit "$RESUME_STATUS"\n'
                   'exit "${UPLOAD_STATUS:-0}"\n')
        self.write('infra/ci/package-image.sh',
                   'echo build >> calls\nexit "${BUILD_STATUS:-0}"\n')
        script = ROOT / 'infra/ci/release.sh'
        cases = [(0, 0, 0, 0, ['resume']),
                 (44, 0, 0, 0, ['resume', 'build', 'upload']),
                 (64, 0, 0, 64, ['resume']),
                 (255, 0, 0, 255, ['resume']),
                 (1, 0, 0, 1, ['resume']),
                 (65, 0, 0, 65, ['resume']),
                 (44, 9, 0, 9, ['resume', 'build']),
                 (44, 0, 7, 7, ['resume', 'build', 'upload'])]
        for resume, build, upload, code, calls in cases:
            with self.subTest(resume=resume, build=build, upload=upload):
                self.write('calls', '')
                result = self.run_script(script, RESUME_STATUS=str(resume),
                                         BUILD_STATUS=str(build), UPLOAD_STATUS=str(upload))
                self.assertEqual(result.returncode, code, result.stderr)
                self.assertEqual((self.cwd / 'calls').read_text().splitlines(), calls)

    def receiver_prefix(self):
        # Execute the real receiver through archive validation, before Docker/DB calls.
        # Root identity, production path, and OS lock are replaced only in this fixture.
        source = (ROOT / 'infra/deploy/receive.sh').read_text()
        source = source.split('python3 - "$temporary"')[0]
        source = source.replace('[[ $EUID == 0 && $# == 2 ]]', '[[ $# == 2 ]]')
        source = source.replace('root=/srv/mupid', 'root=./server')
        source = source.replace('flock -w 300 9 || exit 75', ': # fixture lock')
        (self.cwd / 'server').mkdir(exist_ok=True)
        return self.write('receiver.sh', source)

    def test_resume_absent_incomplete_corrupt_and_valid_archive(self):
        script = self.receiver_prefix()
        release = self.cwd / 'server/releases' / SHA
        result = self.run_script(script, '--resume', SHA)
        self.assertEqual(result.returncode, 44, result.stderr)
        self.assertFalse(release.exists())
        release.mkdir(parents=True)
        archive = gzip.compress(b'fixture image archive')
        (release / 'image.tar.gz').write_bytes(archive)
        self.assertEqual(self.run_script(script, '--resume', SHA).returncode, 65)
        checksum = release / 'image.sha256'
        checksum.write_text('b' * 64 + '\n')
        result = self.run_script(script, '--resume', SHA)
        self.assertEqual(result.returncode, 65)
        self.assertIn('Checksum mismatch', result.stderr)
        checksum.write_text(hashlib.sha256(archive).hexdigest() + '\n')
        result = self.run_script(script, '--resume', SHA)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((release / 'image.tar.gz').read_bytes(), archive)
        self.assertFalse(list(release.glob('upload.*')))

    def test_receiver_rejects_invalid_release(self):
        result = self.run_script(self.receiver_prefix(), '--resume', '../other')
        self.assertEqual(result.returncode, 64)

    def test_forced_command_accepts_only_exact_resume_sha(self):
        entrypoint = (ROOT / 'infra/deploy/ssh-entrypoint.sh').read_text()
        # Stub sudo while exercising actual command parsing and exec arguments.
        self.write('bin/sudo', '#!/bin/sh\nprintf "%s\\n" "$@"\n').chmod(0o755)
        script = self.write('entry.sh', 'export PATH="$PWD/bin:$PATH"\n' + entrypoint)
        result = self.run_script(script, SSH_ORIGINAL_COMMAND='resume ' + SHA)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines(),
                         ['/usr/local/sbin/mupid-receive', '--resume', SHA])
        for command in ['resume ../other', 'resume ' + SHA + '; id',
                        'resume ' + SHA + ' extra', 'sh', 'resume']:
            self.assertEqual(self.run_script(script, SSH_ORIGINAL_COMMAND=command).returncode, 64)

    def test_package_context_excludes_secrets_and_uses_builder_jar(self):
        for name in ['gradlew', 'build.gradle', 'settings.gradle', 'lombok.config',
                     'gradle/wrapper/fixture', 'src/main/fixture']:
            self.write('api/' + name, 'fixture\n')
        self.write('api/.env', 'must-not-be-copied')
        self.write('api/build/release/old.jar', 'must-not-be-copied')
        for name in ['Dockerfile', 'build.Dockerfile']:
            self.write('infra/image/' + name, (ROOT / 'infra/image' / name).read_text())
        wrapper = self.write('package-test.sh', '''
set -eu
chown() { :; }
su() {
  test "$1" = user
  test ! -e "$MUPID_BUILD_DIRECTORY/context/.env"
  test ! -e "$MUPID_BUILD_DIRECTORY/context/build"
  test -f "$MUPID_BUILD_DIRECTORY/context/src/main/fixture"
  cp "$MUPID_BUILD_DIRECTORY/context/Dockerfile" generated.Dockerfile
  printf 'mock docker archive' > "$MUPID_BUILD_DIRECTORY/image.tar"
}
source "$1"
''')
        result = self.run_script(wrapper, str(ROOT / 'infra/ci/package-image.sh'), CI_COMMIT_SHA=SHA)
        self.assertEqual(result.returncode, 0, result.stderr)
        dockerfile = (self.cwd / 'generated.Dockerfile').read_text()
        self.assertIn('build -x test', dockerfile)
        self.assertIn('COPY --from=builder --chown=spring:spring /release/app.jar app.jar', dockerfile)
        self.assertIn('HEALTHCHECK', dockerfile)
        archive = (self.cwd / '.release/image.tar.gz').read_bytes()
        self.assertEqual(gzip.decompress(archive), b'mock docker archive')
        checksum = (self.cwd / '.release/image.tar.gz.sha256').read_text().split()[0]
        self.assertEqual(checksum, hashlib.sha256(archive).hexdigest())

    def test_shell_syntax_and_existing_same_release_checks(self):
        for path in [*ROOT.glob('infra/ci/*.sh'), ROOT / 'infra/deploy/receive.sh',
                     ROOT / 'infra/deploy/ssh-entrypoint.sh']:
            result = subprocess.run([BASH, '-n', str(path)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
        result = subprocess.run([BASH, 'infra/tests/test-same-release.sh'], cwd=ROOT,
                                capture_output=True, text=True, encoding='utf-8', timeout=15)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_ci_yaml_parses_and_artifact_dependency_is_removed(self):
        # Compose's YAML parser handles anchors without a Python YAML dependency.
        # This is a local syntax/structure check, not GitLab's server-side CI Lint.
        source = 'name: ci-fixture\nservices:\n  fixture:\n    image: alpine:3.22\n'
        for name in ['backend', 'release']:
            content = (ROOT / 'infra/ci' / (name + '.yml')).read_text()
            source += 'x-' + name + ':\n' + ''.join('  ' + line + '\n' for line in content.splitlines())
        path = self.write('ci.yaml', source)
        env = {k: v for k, v in os.environ.items()
               if not k.upper().startswith(('DOCKER_', 'COMPOSE_', 'CI_', 'MUPID_'))}
        result = subprocess.run(['docker', '--config', str(self.cwd), 'compose',
                                 '-f', str(path), 'config', '--no-interpolate', '--format', 'json'],
                                env=env, capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stderr)
        data = json.loads(result.stdout)
        verify = data['x-backend']['backend-verify']
        release = data['x-release']
        self.assertNotIn('artifacts', verify)
        self.assertEqual(release['.backend-image']['needs'],
                         [{'job': 'backend-verify', 'artifacts': False}])
        deploy = release['backend-deploy']
        self.assertEqual(deploy['environment']['name'], 'demo')
        self.assertEqual(deploy['resource_group'], 'mupid-demo')
        self.assertFalse(deploy['interruptible'])
        for rule in deploy['rules']:
            self.assertIn('$CI_COMMIT_BRANCH == "develop"', rule['if'])
            self.assertIn('$CI_COMMIT_REF_PROTECTED == "true"', rule['if'])
            self.assertIn('$MUPID_DEPLOY_ENABLED == "true"', rule['if'])
        # Every automatic deployment input must also schedule its verify dependency.
        changes = deploy['rules'][1]['changes']
        self.assertEqual(set(changes), set(verify['rules'][1]['changes']))
        self.assertEqual(release['backend-package']['rules'][0]['changes'], changes)


if __name__ == '__main__':
    unittest.main()
