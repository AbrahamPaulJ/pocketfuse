import socket

import pytest

from pocketfuse import network_helper
from pocketfuse.network_helper import classify_address, detect_addresses, is_public_host, suggest_host


def test_classify_address() -> None:
	assert classify_address('127.0.0.1') == 'loopback'
	assert classify_address('100.100.1.2') == 'tailnet'
	assert classify_address('192.168.0.20') == 'lan'
	assert classify_address('10.0.0.5') == 'lan'
	assert classify_address('8.8.8.8') == 'public'


def test_suggest_host() -> None:
	assert suggest_host(
	[
		{ 'host': '192.168.0.20', 'kind': 'lan' },
		{ 'host': '100.100.1.2', 'kind': 'tailnet' }
	]) == '100.100.1.2'
	assert suggest_host([ { 'host': '192.168.0.20', 'kind': 'lan' } ]) == '192.168.0.20'
	assert suggest_host([ { 'host': '127.0.0.1', 'kind': 'loopback' } ]) is None


def test_is_public_host() -> None:
	assert is_public_host('8.8.8.8') is True
	assert is_public_host('0.0.0.0') is False
	assert is_public_host('192.168.0.20') is False
	assert is_public_host('my-pc.local') is False


def test_detect_addresses_route_first(monkeypatch : pytest.MonkeyPatch) -> None:
	monkeypatch.setattr(socket, 'getaddrinfo', lambda *args : [ (None, None, None, None, ('172.25.0.1', 0)), (None, None, None, None, ('192.168.0.20', 0)) ])
	monkeypatch.setattr(network_helper, 'detect_route_host', lambda : '192.168.0.20')

	assert suggest_host(detect_addresses()) == '192.168.0.20'
