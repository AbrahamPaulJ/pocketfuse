from pocketfuse.types import Locales

LOCALES : Locales =\
{
	'en':
	{
		'help.serve': 'start the FaceFusion API ready for your phone and show the pairing code',
		'help.pair': 'show the pairing code for a FaceFusion API that is already running',
		'help.probe': 'check a FaceFusion API end to end and report every step',
		'help.facefusion_path': 'path to the FaceFusion checkout',
		'help.python_path': 'python interpreter that has the FaceFusion dependencies',
		'help.api_host': 'address to listen on, a tailnet or LAN address of this computer',
		'help.api_port': 'port to listen on',
		'help.api_key': 'API key, read from FACEFUSION_API_KEY when omitted',
		'help.no_api_key': 'leave the API key out of the pairing code and type it on the phone instead',
		'help.base_url': 'base url of the FaceFusion API',
		'help.source_path': 'source image for the probe',
		'help.target_path': 'target image or video for the probe',
		'help.processors': 'processors the probe job runs',
		'address_not_found': 'no tailnet or LAN address found, pass --api-host',
		'address_public': 'refusing a public address, the FaceFusion API speaks plain HTTP and must stay on your LAN or tailnet',
		'api_key_generated': 'generated a new API key for this run, it lives only in this process and the pairing code',
		'api_key_reused': 'using FACEFUSION_API_KEY from the environment, paired phones stay paired',
		'api_key_required': 'set FACEFUSION_API_KEY to use --no-api-key, the phone needs a key to type',
		'api_key_missing': 'no API key, anyone who reaches this port can open a session',
		'server_starting': 'starting the FaceFusion API on {host}:{port}',
		'server_ready': 'FaceFusion API is ready on {host}:{port}',
		'server_not_ready': 'FaceFusion API did not answer within {seconds} seconds',
		'server_stopped': 'FaceFusion API stopped',
		'facefusion_not_found': 'no facefusion.py in {path}',
		'pairing_scan': 'scan this code in Pocketfuse, or open the link on your phone',
		'pairing_secret': 'the code holds the API key, do not share or screenshot it',
		'probe_passed': '{passed} of {total} checks passed',
		'probe_failed': '{failed} of {total} checks failed'
	}
}
