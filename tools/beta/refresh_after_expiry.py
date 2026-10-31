"""Can a refresh token renew a session after the access token expired? Usage: python refresh_after_expiry.py <base_url> <key_file> <wait_seconds>"""
import sys
import time

import httpx


def main() -> None:
	base_url, key_file, wait = sys.argv[1], sys.argv[2], int(sys.argv[3])
	client = httpx.Client(base_url = base_url, timeout = 60)
	tokens = client.post('/session', json = { 'api_key': open(key_file).read().strip() }).json()
	headers = { 'Authorization': 'Bearer ' + tokens.get('access_token') }
	print('fresh session: GET /session', client.get('/session', headers = headers).status_code, flush = True)
	time.sleep(wait)
	response = client.get('/session', headers = headers)
	print('after %d s: GET /session' % wait, response.status_code, response.text[:80], flush = True)
	response = client.put('/session', json = { 'refresh_token': tokens.get('refresh_token') })
	print('after %d s: PUT /session with the refresh token' % wait, response.status_code, response.text[:80], flush = True)
	if response.status_code == 200:
		retry = client.get('/session', headers = { 'Authorization': 'Bearer ' + response.json().get('access_token') })
		print('retry with the new access token', retry.status_code, flush = True)


if __name__ == '__main__':
	main()
