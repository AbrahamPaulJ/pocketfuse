import os
import sys
import tempfile

import pytest

from pocketfuse.server_runner import apply_default_args, create_base_url, create_server_commands, create_server_environment, resolve_library_paths


def test_create_server_commands() -> None:
	assert create_server_commands('python', '100.64.0.2', 8000, [ '--api-session-limit', '4' ]) ==\
	[
		'python', 'facefusion.py', 'api', '--api-host', '100.64.0.2', '--api-port', '8000', '--api-session-limit', '4'
	]


def test_apply_default_args() -> None:
	assert apply_default_args([]) == [ '--api-session-limit', '4', '--video-memory-strategy', 'moderate' ]
	assert apply_default_args([ '--api-session-limit', '1' ]) == [ '--api-session-limit', '1', '--video-memory-strategy', 'moderate' ]
	assert apply_default_args([ '--video-memory-strategy', 'strict' ]) == [ '--video-memory-strategy', 'strict', '--api-session-limit', '4' ]
	assert apply_default_args([ '--api-session-limit=1', '--video-memory-strategy=strict' ]) == [ '--api-session-limit=1', '--video-memory-strategy=strict' ]
	assert apply_default_args([ '--execution-providers', 'cuda' ]) == [ '--execution-providers', 'cuda', '--api-session-limit', '4', '--video-memory-strategy', 'moderate' ]


def test_create_server_environment(monkeypatch : pytest.MonkeyPatch) -> None:
	monkeypatch.setenv('FACEFUSION_API_KEY', 'inherited')

	assert create_server_environment(sys.executable, 'fresh').get('FACEFUSION_API_KEY') == 'fresh'
	assert 'FACEFUSION_API_KEY' not in create_server_environment(sys.executable, None)


def test_create_base_url() -> None:
	assert create_base_url('0.0.0.0', 8000) == 'http://127.0.0.1:8000'
	assert create_base_url('100.64.0.2', 9000) == 'http://100.64.0.2:9000'


def test_resolve_library_paths(monkeypatch : pytest.MonkeyPatch) -> None:
	with tempfile.TemporaryDirectory() as temp_path:
		python_path = os.path.join(temp_path, 'Scripts', 'python.exe')
		library_path = os.path.join(temp_path, 'Lib', 'site-packages', 'nvidia', 'cu13', 'bin', 'x86_64')
		empty_path = os.path.join(temp_path, 'Lib', 'site-packages', 'nvidia', 'cudnn', 'include')
		os.makedirs(library_path)
		os.makedirs(empty_path)
		open(os.path.join(library_path, 'cublasLt64_13.dll'), 'w').close()

		monkeypatch.setattr(sys, 'platform', 'win32')
		assert resolve_library_paths(python_path) == [ library_path ]

		monkeypatch.setattr(sys, 'platform', 'linux')
		assert resolve_library_paths(python_path) == []
