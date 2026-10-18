import os

import httpx
import pytest

from pocketfuse.prober import create_probe_result, expect_status, format_probe_report, run_probe, to_websocket_url


def test_expect_status() -> None:
	assert expect_status(httpx.Response(201), 201) == ('passed', '201')
	assert expect_status(httpx.Response(409), 201) == ('failed', 'expected 201, got 409')


def test_create_probe_result() -> None:
	def broken_check() -> None:
		raise httpx.ConnectError('refused')

	probe_result = create_probe_result('root', broken_check) #type:ignore[arg-type]

	assert probe_result.get('status') == 'failed'
	assert probe_result.get('detail') == 'ConnectError: refused'


def test_to_websocket_url() -> None:
	assert to_websocket_url('http://127.0.0.1:8000', '/stream') == 'ws://127.0.0.1:8000/stream'
	assert to_websocket_url('https://pc.tailnet.ts.net', '/metrics') == 'wss://pc.tailnet.ts.net/metrics'


def test_format_probe_report() -> None:
	probe_report = format_probe_report(
	[
		{ 'name': 'root', 'status': 'passed', 'duration': 0.01, 'detail': 'FaceFusion v4' },
		{ 'name': 'session rejects a wrong key', 'status': 'skipped', 'duration': 0.0, 'detail': 'no API key configured' }
	])

	assert probe_report.split('\n')[0].startswith('ok    root')
	assert probe_report.split('\n')[1].startswith('skip  session rejects a wrong key')


@pytest.mark.skipif(not os.getenv('POCKETFUSE_TEST_BASE_URL'), reason = 'needs a running FaceFusion API')
def test_run_probe() -> None:
	probe_report = run_probe(os.getenv('POCKETFUSE_TEST_BASE_URL'), os.getenv('FACEFUSION_API_KEY'), os.getenv('POCKETFUSE_TEST_SOURCE'), os.getenv('POCKETFUSE_TEST_TARGET'), [ 'face_swapper' ])

	assert [ probe_result.get('name') for probe_result in probe_report if probe_result.get('status') == 'failed' ] == []
