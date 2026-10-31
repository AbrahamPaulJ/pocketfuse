"""A /stream frame needing a model that is not downloaded crashes the socket; can the session still be closed afterwards?
Usage: python stream_missing_model.py <base_url> <key_file> <source> <frame> <model>"""
import sys
import time

import httpx
from websockets.sync.client import connect


def main() -> None:
	base_url, key_file, source_path, frame_path, model = sys.argv[1:6]
	client = httpx.Client(base_url = base_url, timeout = 300)
	tokens = client.post('/session', json = { 'api_key': open(key_file).read().strip() }).json()
	client.headers['Authorization'] = 'Bearer ' + tokens.get('access_token')
	with open(source_path, 'rb') as file:
		source_id = client.post('/assets', params = { 'type': 'source' }, files = { 'file': ('s.jpg', file) }).json().get('asset_ids')[0]
	client.put('/state', params = { 'action': 'select', 'type': 'source' }, json = { 'asset_ids': [ source_id ] })
	client.put('/state', json = { 'processors': [ 'face_swapper' ], 'face_selector_mode': 'one', 'face_swapper_model': model })
	try:
		with connect(base_url.replace('http', 'ws') + '/stream', subprotocols = [ 'access_token.' + tokens.get('access_token') ], max_size = 64 * 1024 * 1024) as websocket:
			websocket.send(open(frame_path, 'rb').read())
			websocket.recv(timeout = 60)
	except Exception as exception:
		print('stream', type(exception).__name__, exception)
	for wait in [ 0, 5, 30 ]:
		time.sleep(wait)
		response = client.delete('/session')
		print('after %2d s: DELETE /session' % wait, response.status_code, response.text[:80])
		if response.status_code in [ 200, 206 ]:
			break
	print('GET /session afterwards', client.get('/session').status_code)


if __name__ == '__main__':
	main()
