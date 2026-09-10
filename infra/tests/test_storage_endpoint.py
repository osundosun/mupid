"""Prevent a false positive when the API points to a different storage service."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('verify_storage',
    Path(__file__).resolve().parents[1] / 'deploy/verify-storage.py')
storage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(storage)


class StorageEndpointTest(unittest.TestCase):
    def test_managed_endpoint(self):
        for value in ('http://storage:8333', 'http://storage:8333/'):
            storage.validate_endpoint(value)

    def test_other_endpoints_are_not_silently_ignored(self):
        for value in ('', 'http://localhost:8333', 'http://remote:8333',
                      'https://storage:8333', 'http://storage:80',
                      'http://storage:8333/bucket', 'http://storage:8333?x=1',
                      'http://user:password@storage:8333', 'http://storage:8333#fragment'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                storage.validate_endpoint(value)
