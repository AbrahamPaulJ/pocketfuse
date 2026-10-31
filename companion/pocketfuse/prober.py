import os
import time
from typing import Callable, List, Optional, Tuple

import httpx
from websockets.exceptions import WebSocketException
from websockets.sync.client import connect

from pocketfuse.types import ApiKey, ApiSession, ProbeReport, ProbeResult, ProbeStatus, Url

JOB_TIMEOUT = 900
ProbeCheck = Callable[[], Tuple[ProbeStatus, str]]


def create_probe_result(name : str, check : ProbeCheck) -> ProbeResult:
	start_time = time.perf_counter()

	try:
		status, detail = check()
	except (httpx.HTTPError, WebSocketException, OSError, ValueError, KeyError, TypeError) as exception:
		status, detail = 'failed', type(exception).__name__ + ': ' + str(exception)

	return\
	{
		'name': name,
		'status': status,
		'duration': time.perf_counter() - start_time,
		'detail': detail
	}


def expect_status(response : httpx.Response, status_code : int) -> Tuple[ProbeStatus, str]:
	if response.status_code == status_code:
		return 'passed', str(response.status_code)
	return 'failed', 'expected ' + str(status_code) + ', got ' + str(response.status_code)


def to_websocket_url(base_url : Url, path : str) -> Url:
	return base_url.replace('https://', 'wss://').replace('http://', 'ws://') + path


def upload_asset(client : httpx.Client, asset_type : str, file_path : str) -> httpx.Response:
	with open(file_path, 'rb') as file:
		return client.post('/assets', params = { 'type': asset_type }, files = { 'file': (os.path.basename(file_path), file) })


def wait_for_job(client : httpx.Client, job_id : str) -> List[str]:
	deadline = time.monotonic() + JOB_TIMEOUT
	step_statuses : List[str] = []

	while time.monotonic() < deadline:
		job = client.get('/jobs/' + job_id).json()
		step_statuses = [ step.get('status') for step in job.get('steps') ]

		if 'failed' in step_statuses or all(step_status == 'completed' for step_status in step_statuses):
			return step_statuses

		time.sleep(0.5)

	return step_statuses


def find_output_asset_id(client : httpx.Client, job_id : str) -> Optional[str]:
	for _ in range(40):
		for asset in client.get('/assets').json().get('assets'):
			if asset.get('type') == 'output' and asset.get('name', '').startswith(job_id):
				return asset.get('id')
		time.sleep(0.25)

	return None


def run_probe(base_url : Url, api_key : Optional[ApiKey], source_path : str, target_path : str, processors : List[str]) -> ProbeReport:
	client = httpx.Client(base_url = base_url, timeout = 120)
	report : ProbeReport = []
	context : dict[str, str] = {}
	api_session : ApiSession =\
	{
		'access_token': '',
		'refresh_token': ''
	}

	def authorize() -> None:
		client.headers['Authorization'] = 'Bearer ' + api_session.get('access_token')

	def check_root() -> Tuple[ProbeStatus, str]:
		root = client.get('/').json()
		return 'passed', str(root.get('name')) + ' ' + str(root.get('version'))

	def check_wrong_key() -> Tuple[ProbeStatus, str]:
		if not api_key:
			return 'skipped', 'no API key configured'
		return expect_status(client.post('/session', json = { 'api_key': api_key + 'x' }), 401)

	def check_create_session() -> Tuple[ProbeStatus, str]:
		response = client.post('/session', json = { 'api_key': api_key } if api_key else {})

		if response.status_code == 201:
			api_session['access_token'] = response.json().get('access_token')
			api_session['refresh_token'] = response.json().get('refresh_token')
			authorize()
		return expect_status(response, 201)

	def check_capabilities() -> Tuple[ProbeStatus, str]:
		arguments = client.get('/capabilities').json().get('arguments')
		return 'passed', str(len(arguments)) + ' argument groups'

	def check_upload_source() -> Tuple[ProbeStatus, str]:
		response = upload_asset(client, 'source', source_path)

		if response.status_code == 201:
			context['source_id'] = response.json().get('asset_ids')[0]
		return expect_status(response, 201)

	def check_upload_target() -> Tuple[ProbeStatus, str]:
		response = upload_asset(client, 'target', target_path)

		if response.status_code == 201:
			context['target_id'] = response.json().get('asset_ids')[0]
		return expect_status(response, 201)

	def check_capture_face() -> Tuple[ProbeStatus, str]:
		response = client.get('/assets/' + context.get('source_id', ''), params = { 'action': 'capture', 'subject': 'face', 'resolution': '128x128' })

		if response.status_code == 200 and response.content:
			return 'passed', str(len(response.content)) + ' bytes'
		return expect_status(response, 200)

	def check_select() -> Tuple[ProbeStatus, str]:
		source_response = client.put('/state', params = { 'action': 'select', 'type': 'source' }, json = { 'asset_ids': [ context.get('source_id') ] })
		target_response = client.put('/state', params = { 'action': 'select', 'type': 'target' }, json = { 'asset_id': context.get('target_id') })

		if source_response.status_code != 200:
			return expect_status(source_response, 200)
		return expect_status(target_response, 200)

	def check_invalid_state() -> Tuple[ProbeStatus, str]:
		return expect_status(client.put('/state', json = { 'processors': processors, 'unknown_option': True }), 400)

	def check_set_state() -> Tuple[ProbeStatus, str]:
		return expect_status(client.put('/state', json = { 'processors': processors }), 200)

	def check_job() -> Tuple[ProbeStatus, str]:
		job_id = client.post('/jobs').json().get('job_id')
		context['job_id'] = job_id
		client.post('/jobs/' + job_id, params = { 'action': 'add' }, json = { 'processors': processors })
		client.patch('/jobs/' + job_id, params = { 'action': 'submit' })
		run_response = client.patch('/jobs/' + job_id, params = { 'action': 'run' })

		if run_response.status_code != 202:
			return expect_status(run_response, 202)

		step_statuses = wait_for_job(client, job_id)

		if step_statuses and all(step_status == 'completed' for step_status in step_statuses):
			return 'passed', ', '.join(step_statuses)
		return 'failed', ', '.join(step_statuses) or 'timed out'

	def check_download_output() -> Tuple[ProbeStatus, str]:
		output_id = find_output_asset_id(client, context.get('job_id', ''))

		if output_id:
			response = client.get('/assets/' + output_id, params = { 'action': 'download' })

			if response.status_code == 200 and response.content:
				return 'passed', str(len(response.content)) + ' bytes'
			return expect_status(response, 200)
		return 'failed', 'no output asset'

	def check_metrics() -> Tuple[ProbeStatus, str]:
		metrics = client.get('/metrics').json()
		return 'passed', ', '.join(sorted(metrics.keys()))

	def check_stream() -> Tuple[ProbeStatus, str]:
		subprotocol = 'access_token.' + api_session.get('access_token')

		with open(source_path, 'rb') as file:
			image_buffer = file.read()

		with connect(to_websocket_url(base_url, '/stream'), subprotocols = [ subprotocol ], max_size = 64 * 1024 * 1024) as websocket: #type:ignore[list-item]
			websocket.send(image_buffer)
			output_buffer = websocket.recv(timeout = 120)

		if isinstance(output_buffer, bytes) and output_buffer[:2] == b'\xff\xd8':
			return 'passed', str(len(output_buffer)) + ' bytes jpeg'
		return 'failed', 'no jpeg returned'

	def check_refresh_session() -> Tuple[ProbeStatus, str]:
		old_access_token = api_session.get('access_token')
		response = client.put('/session', json = { 'refresh_token': api_session.get('refresh_token') })

		if response.status_code != 200:
			return expect_status(response, 200)

		api_session['access_token'] = response.json().get('access_token')
		api_session['refresh_token'] = response.json().get('refresh_token')
		old_response = client.get('/session', headers = { 'Authorization': 'Bearer ' + old_access_token })
		authorize()

		if old_response.status_code == 401:
			return 'passed', 'old token rejected'
		return 'failed', 'old token answered ' + str(old_response.status_code)

	def check_destroy_session() -> Tuple[ProbeStatus, str]:
		response = client.delete('/session')

		if response.status_code != 200:
			return expect_status(response, 200)
		return expect_status(client.get('/session'), 401)

	checks : List[Tuple[str, ProbeCheck]] =\
	[
		('root', check_root),
		('session rejects a wrong key', check_wrong_key),
		('session opens', check_create_session),
		('capabilities', check_capabilities),
		('upload source', check_upload_source),
		('upload target', check_upload_target),
		('capture source face', check_capture_face),
		('select source and target', check_select),
		('state rejects an unknown key', check_invalid_state),
		('state sets processors', check_set_state),
		('job runs to completion', check_job),
		('download output', check_download_output),
		('metrics', check_metrics),
		('stream websocket', check_stream),
		('refresh rotates tokens', check_refresh_session),
		('session closes', check_destroy_session)
	]

	for name, check in checks:
		report.append(create_probe_result(name, check))

	client.close()
	return report


def format_probe_report(report : ProbeReport) -> str:
	lines = []
	symbols =\
	{
		'passed': 'ok  ',
		'failed': 'FAIL',
		'skipped': 'skip'
	}

	for probe_result in report:
		lines.append('%s  %-30s %8.2f s  %s' % (symbols.get(probe_result.get('status')), probe_result.get('name'), probe_result.get('duration'), probe_result.get('detail')))

	return '\n'.join(lines)
