import secrets
from typing import Optional
from urllib.parse import parse_qs, urlencode, urlsplit

import segno

from pocketfuse.types import ApiKey, Host, Pairing, Port, Url

PAIRING_SCHEME = 'pocketfuse'
PAIRING_HOST = 'pair'


def create_api_key() -> ApiKey:
	return secrets.token_urlsafe(24)


def create_pairing_url(host : Host, port : Port, api_key : Optional[ApiKey]) -> Url:
	query =\
	{
		'host': host,
		'port': str(port)
	}

	if api_key:
		query['key'] = api_key

	return PAIRING_SCHEME + '://' + PAIRING_HOST + '?' + urlencode(query)


def parse_pairing_url(pairing_url : Url) -> Optional[Pairing]:
	url = urlsplit(pairing_url)

	if url.scheme == PAIRING_SCHEME and url.netloc == PAIRING_HOST:
		query = parse_qs(url.query)
		host = query.get('host', [ '' ])[0]
		port = query.get('port', [ '' ])[0]
		api_key = query.get('key', [ None ])[0]

		if host and port.isdigit() and 0 < int(port) < 65536:
			return\
			{
				'host': host,
				'port': int(port),
				'api_key': api_key
			}

	return None


def render_pairing_code(pairing_url : Url) -> str:
	qr_code = segno.make_qr(pairing_url, error = 'm')
	lines = []

	for row in qr_code.matrix_iter(scale = 1, border = 2):
		lines.append(''.join('  ' if cell else '██' for cell in row))

	return '\n'.join(lines)
