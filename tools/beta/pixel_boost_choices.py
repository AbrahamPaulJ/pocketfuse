"""Pixel boost choices follow the launch model, not the session's model. Usage: python pixel_boost_choices.py <base_url> <key_file>"""
import sys

import httpx


def main() -> None:
	base_url, key_file = sys.argv[1:3]
	client = httpx.Client(base_url = base_url, timeout = 60)
	tokens = client.post('/session', json = { 'api_key': open(key_file).read().strip() }).json()
	client.headers['Authorization'] = 'Bearer ' + tokens.get('access_token')
	print('model inswapper_128_fp16 ->', client.put('/state', json = { 'face_swapper_model': 'inswapper_128_fp16' }).status_code)
	response = client.put('/state', json = { 'face_swapper_pixel_boost': '128x128' })
	print('pixel boost 128x128      ->', response.status_code, response.text[:80])
	response = client.put('/state', json = { 'face_swapper_model': 'inswapper_128_fp16', 'face_swapper_pixel_boost': '128x128' })
	print('both in one request      ->', response.status_code, response.text[:80])
	client.delete('/session')


if __name__ == '__main__':
	main()
