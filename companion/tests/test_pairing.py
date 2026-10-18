from pocketfuse.pairing import create_api_key, create_pairing_url, parse_pairing_url, render_pairing_code


def test_create_api_key() -> None:
	api_key = create_api_key()

	assert len(api_key) >= 32
	assert api_key != create_api_key()


def test_create_pairing_url() -> None:
	assert create_pairing_url('100.64.0.2', 8000, 'abc+def') == 'pocketfuse://pair?host=100.64.0.2&port=8000&key=abc%2Bdef'
	assert create_pairing_url('192.168.1.20', 9000, None) == 'pocketfuse://pair?host=192.168.1.20&port=9000'


def test_parse_pairing_url() -> None:
	assert parse_pairing_url(create_pairing_url('100.64.0.2', 8000, 'abc+def')) ==\
	{
		'host': '100.64.0.2',
		'port': 8000,
		'api_key': 'abc+def'
	}
	assert parse_pairing_url('pocketfuse://pair?host=192.168.1.20&port=8000').get('api_key') is None
	assert parse_pairing_url('pocketfuse://pair?host=192.168.1.20&port=99999') is None
	assert parse_pairing_url('pocketfuse://other?host=192.168.1.20&port=8000') is None
	assert parse_pairing_url('https://example.com/pair?host=a&port=1') is None


def test_render_pairing_code() -> None:
	pairing_code = render_pairing_code(create_pairing_url('100.64.0.2', 8000, create_api_key()))
	lines = pairing_code.split('\n')

	assert len(lines) > 20
	assert len(set(len(line) for line in lines)) == 1
	assert len(lines[0]) == len(lines) * 2
	assert '#' in render_pairing_code(create_pairing_url('100.64.0.2', 8000, None), '##')
