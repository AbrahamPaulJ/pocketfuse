import os
import signal
import sys
from argparse import Namespace
from types import FrameType
from typing import List, NoReturn, Optional

from pocketfuse import network_helper, pairing, prober, program, server_runner, translator
from pocketfuse.types import ApiKey, Host, Port


def cli() -> None:
	signal.signal(signal.SIGINT, signal_exit)
	args, server_args = program.create_program().parse_known_args()

	if args.command == 'serve':
		sys.exit(serve(args, server_args))
	if args.command == 'pair':
		sys.exit(pair(args))
	if args.command == 'probe':
		sys.exit(probe(args))

	program.create_program().print_help()


def signal_exit(signum : int, frame : Optional[FrameType]) -> NoReturn:
	sys.exit(0)


def resolve_host(api_host : Optional[Host]) -> Optional[Host]:
	if api_host:
		if network_helper.is_public_host(api_host):
			print(translator.get('address_public'))
			return None
		return api_host

	host = network_helper.suggest_host(network_helper.detect_addresses())

	if not host:
		print(translator.get('address_not_found'))
	return host


def print_pairing(host : Host, port : Port, api_key : Optional[ApiKey], no_api_key : bool) -> None:
	pairing_url = pairing.create_pairing_url(host, port, None if no_api_key else api_key)

	print(pairing.render_pairing_code(pairing_url))
	print(translator.get('pairing_scan'))
	print(pairing_url)

	if api_key and not no_api_key:
		print(translator.get('pairing_secret'))
	if not api_key:
		print(translator.get('api_key_missing'))


def serve(args : Namespace, server_args : List[str]) -> int:
	host = resolve_host(args.api_host)
	facefusion_path = os.path.abspath(args.facefusion_path)

	if not host:
		return 1

	if not os.path.isfile(os.path.join(facefusion_path, 'facefusion.py')):
		print(translator.get('facefusion_not_found', path = facefusion_path))
		return 1

	api_key = pairing.create_api_key()
	print(translator.get('api_key_generated'))
	print(translator.get('server_starting', host = host, port = args.api_port))
	process = server_runner.start_server(facefusion_path, args.python_path, host, args.api_port, api_key, server_args)
	base_url = server_runner.create_base_url(host, args.api_port)

	if not server_runner.wait_for_server(base_url, process, server_runner.READY_TIMEOUT):
		print(translator.get('server_not_ready', seconds = server_runner.READY_TIMEOUT))
		server_runner.stop_server(process)
		return 1

	print(translator.get('server_ready', host = host, port = args.api_port))
	print_pairing(host, args.api_port, api_key, args.no_api_key)

	try:
		return process.wait()
	finally:
		server_runner.stop_server(process)
		print(translator.get('server_stopped'))


def pair(args : Namespace) -> int:
	host = resolve_host(args.api_host)

	if host:
		print_pairing(host, args.api_port, args.api_key, args.no_api_key)
		return 0
	return 1


def probe(args : Namespace) -> int:
	report = prober.run_probe(args.base_url, args.api_key, args.source_path, args.target_path, args.processors)
	failed_total = len([ probe_result for probe_result in report if probe_result.get('status') == 'failed' ])
	passed_total = len([ probe_result for probe_result in report if probe_result.get('status') == 'passed' ])

	print(prober.format_probe_report(report))

	if failed_total:
		print(translator.get('probe_failed', failed = failed_total, total = len(report)))
		return 1

	print(translator.get('probe_passed', passed = passed_total, total = len(report)))
	return 0
