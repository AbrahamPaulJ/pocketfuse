import os
import subprocess
import sys
import time
from typing import Dict, List, Optional

import httpx

from pocketfuse.types import ApiKey, Host, Port, Url

READY_TIMEOUT = 600
DEFAULT_SERVER_ARGS =\
{
	'--api-session-limit': '4',
	'--video-memory-strategy': 'moderate'
}


def resolve_library_paths(python_path : str) -> List[str]:
	library_paths : List[str] = []
	nvidia_path = os.path.join(os.path.dirname(os.path.dirname(python_path)), 'Lib', 'site-packages', 'nvidia')

	if sys.platform == 'win32' and os.path.isdir(nvidia_path):
		for directory_path, _, file_names in sorted(os.walk(nvidia_path)):
			if any(file_name.endswith('.dll') for file_name in file_names):
				library_paths.append(directory_path)

	return library_paths


def create_server_environment(python_path : str, api_key : Optional[ApiKey]) -> Dict[str, str]:
	server_environment = os.environ.copy()
	library_paths = resolve_library_paths(python_path)

	if library_paths:
		library_paths.append(server_environment.get('PATH', ''))
		server_environment['PATH'] = os.pathsep.join(library_paths)

	server_environment.pop('FACEFUSION_API_KEY', None)

	if api_key:
		server_environment['FACEFUSION_API_KEY'] = api_key

	return server_environment


def apply_default_args(server_args : List[str]) -> List[str]:
	__server_args__ = list(server_args)

	for argument_name, argument_value in DEFAULT_SERVER_ARGS.items():
		if argument_name not in __server_args__:
			__server_args__.extend([ argument_name, argument_value ])

	return __server_args__


def create_server_commands(python_path : str, host : Host, port : Port, server_args : List[str]) -> List[str]:
	return [ python_path, 'facefusion.py', 'api', '--api-host', host, '--api-port', str(port) ] + server_args


def start_server(facefusion_path : str, python_path : str, host : Host, port : Port, api_key : Optional[ApiKey], server_args : List[str]) -> subprocess.Popen[bytes]:
	commands = create_server_commands(python_path, host, port, server_args)
	server_environment = create_server_environment(python_path, api_key)
	return subprocess.Popen(commands, cwd = facefusion_path, env = server_environment)


def create_base_url(host : Host, port : Port) -> Url:
	if host == '0.0.0.0':
		host = '127.0.0.1'
	return 'http://' + host + ':' + str(port)


def wait_for_server(base_url : Url, process : subprocess.Popen[bytes], timeout : float) -> bool:
	deadline = time.monotonic() + timeout

	while time.monotonic() < deadline and process.poll() is None:
		try:
			if httpx.get(base_url + '/', timeout = 2).status_code == 200:
				return True
		except httpx.HTTPError:
			pass
		time.sleep(1)

	return False


def stop_server(process : subprocess.Popen[bytes]) -> None:
	if process.poll() is None:
		if sys.platform == 'win32':
			subprocess.run([ 'taskkill', '/F', '/T', '/PID', str(process.pid) ], stdout = subprocess.DEVNULL, stderr = subprocess.DEVNULL)
		else:
			process.terminate()

		try:
			process.wait(timeout = 10)
		except subprocess.TimeoutExpired:
			process.kill()
