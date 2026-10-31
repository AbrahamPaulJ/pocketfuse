"""What a client learns about a failed job, and the deep_swapper default. Usage: python failed_job_reason.py <base_url> <key_file> <no_face_image> <target_image>"""
import sys
import time

import httpx


def main() -> None:
	base_url, key_file, source_path, target_path = sys.argv[1:5]
	client = httpx.Client(base_url = base_url, timeout = 120)
	tokens = client.post('/session', json = { 'api_key': open(key_file).read().strip() }).json()
	client.headers['Authorization'] = 'Bearer ' + tokens.get('access_token')

	try:
		deep_swapper_model = client.get('/capabilities').json().get('arguments').get('deep_swapper').get('deep_swapper_model')
		print('deep_swapper_model default:', deep_swapper_model.get('default'), '| choices:', len(deep_swapper_model.get('choices')))
		with open(source_path, 'rb') as source_file:
			source_ids = client.post('/assets', params = { 'type': 'source' }, files = { 'file': ('source.jpg', source_file, 'image/jpeg') }).json().get('asset_ids')
		with open(target_path, 'rb') as target_file:
			target_id = client.post('/assets', params = { 'type': 'target' }, files = { 'file': ('target.jpg', target_file, 'image/jpeg') }).json().get('asset_ids')[0]
		client.put('/state', params = { 'action': 'select', 'type': 'source' }, json = { 'asset_ids': source_ids })
		client.put('/state', params = { 'action': 'select', 'type': 'target' }, json = { 'asset_id': target_id })
		job_id = client.post('/jobs').json().get('job_id')
		client.post('/jobs/' + job_id, params = { 'action': 'add' }, json = { 'processors': [ 'face_swapper' ] })
		client.patch('/jobs/' + job_id, params = { 'action': 'submit' })
		client.patch('/jobs/' + job_id, params = { 'action': 'run' })
		started = time.perf_counter()
		while time.perf_counter() - started < 60 and client.get('/jobs', params = { 'status': 'queued' }).json():
			time.sleep(0.5)
		print('GET /jobs/{id} ->', client.get('/jobs/' + job_id).text)
		print('GET /jobs?status=failed ->', client.get('/jobs', params = { 'status': 'failed' }).text[:300])
	finally:
		print('DELETE /session ->', client.delete('/session').status_code)


if __name__ == '__main__':
	main()
