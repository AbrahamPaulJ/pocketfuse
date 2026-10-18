import os
import sys
from argparse import ArgumentParser, HelpFormatter

from pocketfuse import metadata, translator


def create_help_formatter(prog : str) -> HelpFormatter:
	return HelpFormatter(prog, max_help_position = 50)


def create_api_key_program() -> ArgumentParser:
	program = ArgumentParser(add_help = False)
	program.add_argument('--api-key', help = translator.get('help.api_key'), default = os.getenv('FACEFUSION_API_KEY'))
	return program


def create_api_port_program() -> ArgumentParser:
	program = ArgumentParser(add_help = False)
	program.add_argument('--api-port', help = translator.get('help.api_port'), type = int, default = 8000)
	return program


def create_program() -> ArgumentParser:
	program = ArgumentParser(formatter_class = create_help_formatter)
	program.add_argument('-v', '--version', version = metadata.get('name') + ' ' + metadata.get('version'), action = 'version')
	sub_program = program.add_subparsers(dest = 'command')

	serve_program = sub_program.add_parser('serve', help = translator.get('help.serve'), parents = [ create_api_port_program() ], formatter_class = create_help_formatter)
	serve_program.add_argument('--facefusion-path', help = translator.get('help.facefusion_path'), default = os.path.join('..', 'facefusion'))
	serve_program.add_argument('--python-path', help = translator.get('help.python_path'), default = sys.executable)
	serve_program.add_argument('--api-host', help = translator.get('help.api_host'))
	serve_program.add_argument('--no-api-key', help = translator.get('help.no_api_key'), action = 'store_true')

	pair_program = sub_program.add_parser('pair', help = translator.get('help.pair'), parents = [ create_api_key_program(), create_api_port_program() ], formatter_class = create_help_formatter)
	pair_program.add_argument('--api-host', help = translator.get('help.api_host'))
	pair_program.add_argument('--no-api-key', help = translator.get('help.no_api_key'), action = 'store_true')

	probe_program = sub_program.add_parser('probe', help = translator.get('help.probe'), parents = [ create_api_key_program() ], formatter_class = create_help_formatter)
	probe_program.add_argument('--base-url', help = translator.get('help.base_url'), default = 'http://127.0.0.1:8000')
	probe_program.add_argument('--source-path', help = translator.get('help.source_path'), required = True)
	probe_program.add_argument('--target-path', help = translator.get('help.target_path'), required = True)
	probe_program.add_argument('--processors', help = translator.get('help.processors'), nargs = '+', default = [ 'face_swapper' ])

	return program
