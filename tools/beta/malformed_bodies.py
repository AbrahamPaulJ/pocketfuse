"""Malformed JSON bodies and mixed multi-file uploads. Usage: python malformed_bodies.py <base_url> <key_file> <image_path>"""
import sys

import httpx

JSON_HEADERS = { 'Content-Type': 'application/json' }


def main() -> None:
	base_url, key_file, image_path = sys.argv[1], sys.argv[2], sys.argv[3]
	client = httpx.Client(base_url = base_url, timeout = 60)
	print('POST /session body [] ->', client.post('/session', content = b'[]', headers = JSON_HEADERS).status_code)
	print('POST /session api_key 123 ->', client.post('/session', json = { 'api_key': 123 }).status_code)
	print('PUT /session body [] ->', client.put('/session', content = b'[]', headers = JSON_HEADERS).status_code)
	tokens = client.post('/session', json = { 'api_key': open(key_file).read().strip() }).json()
	client.headers['Authorization'] = 'Bearer ' + tokens.get('access_token')

	try:
		print('PUT /state body [] ->', client.put('/state', content = b'[]', headers = JSON_HEADERS).status_code)
		print('PUT /state?action=select&type=source body [] ->', client.put('/state', params = { 'action': 'select', 'type': 'source' }, content = b'[]', headers = JSON_HEADERS).status_code)
		image_bytes = open(image_path, 'rb').read()
		response = client.post('/assets', params = { 'type': 'source' }, files = [ ('file', ('a.jpg', image_bytes, 'image/jpeg')), ('file', ('b.txt', b'not media', 'text/plain')) ])
		print('POST /assets [a.jpg, b.txt] ->', response.status_code, response.text[:120])
		response = client.post('/assets', params = { 'type': 'source' }, files = [ ('file', ('b.txt', b'not media', 'text/plain')), ('file', ('a.jpg', image_bytes, 'image/jpeg')) ])
		print('POST /assets [b.txt, a.jpg] ->', response.status_code, response.text[:120])
	finally:
		print('DELETE /session ->', client.delete('/session').status_code)


if __name__ == '__main__':
	main()
