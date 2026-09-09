"""Local infrastructure checks only; no daemon, application, or network required.

Run: python -m unittest discover -s infra/tests -v
Requires the Docker Compose CLI. This suite is deliberately not added to CI.
"""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
REQUIRED = (
    'MUPID_MYSQL_USER', 'MUPID_MYSQL_PASSWORD', 'MUPID_JWT_SECRET',
    'MUPID_REDIS_KEY_PREFIX', 'MUPID_GMAIL_CLIENT_ID', 'MUPID_GMAIL_CLIENT_SECRET',
    'MUPID_GMAIL_TOKEN', 'MUPID_GMAIL_SEND_ADDRESS', 'MUPID_S3_ACCESS_KEY',
    'MUPID_S3_SECRET', 'MUPID_S3_BUCKET', 'MUPID_CLIENT_WEBSITE',
)


class DeployConfigTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.values = {key: 'fixture-only' for key in REQUIRED}
        self.values.update(
            MUPID_HTTP_PORT='80', MUPID_HTTPS_PORT='443',
            MUPID_API_IMAGE='mupid-api:' + 'a' * 40,
            MUPID_MYSQL_ROOT_PASSWORD='root-fixture-only',
            MUPID_S3_ENDPOINT='',
            MUPID_CLIENT_WEBSITE='https://example.invalid',
        )

    def compose(self):
        env_file = self.directory / 'fixture.env'
        env_file.write_text(''.join(f'{k}={v}\n' for k, v in self.values.items()))
        # Ignore the developer's secrets and Docker credentials, even if set locally.
        environment = {k: v for k, v in os.environ.items()
                       if not k.upper().startswith(('MUPID_', 'COMPOSE_', 'DOCKER_'))}
        return subprocess.run(
            ['docker', '--config', str(self.directory), 'compose',
             '--env-file', str(env_file), '-f', str(ROOT / 'infra/deploy/compose.yml'),
             'config', '--format', 'json'],
            capture_output=True, text=True, env=environment, timeout=30,
        )

    def test_complete_config_forwards_app_values_without_root_secret(self):
        result = self.compose()
        self.assertEqual(result.returncode, 0, result.stderr)
        services = json.loads(result.stdout)['services']
        api = services['api']['environment']
        for key in REQUIRED:
            self.assertEqual(api[key], self.values[key], key)
        self.assertEqual(api['MUPID_S3_ENDPOINT'], '')
        self.assertEqual(api['MUPID_REDIS_HOST'], 'redis')
        self.assertEqual(api['MUPID_REDIS_PORT'], '6379')
        self.assertNotIn('MUPID_MYSQL_ROOT_PASSWORD', api)
        self.assertNotIn('MYSQL_ROOT_PASSWORD', api)
        for service in ('api', 'mysql', 'redis'):
            self.assertFalse(services[service].get('ports'), service)

    def test_missing_or_empty_required_values_reject_configuration(self):
        for key in REQUIRED:
            for value in (None, ''):
                with self.subTest(key=key, value=value):
                    previous = self.values.pop(key)
                    if value is not None:
                        self.values[key] = value
                    result = self.compose()
                    self.values[key] = previous
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn(key, result.stderr)
                    self.assertNotIn('root-fixture-only', result.stderr)

    def test_custom_storage_endpoint(self):
        self.values['MUPID_S3_ENDPOINT'] = 'http://storage:8333'
        result = self.compose()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)['services']['api']['environment']
                         ['MUPID_S3_ENDPOINT'], 'http://storage:8333')


if __name__ == '__main__':
    unittest.main()
