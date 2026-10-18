from typing import Optional

METADATA =\
{
	'name': 'Pocketfuse',
	'description': 'Pair your phone with your own FaceFusion server',
	'version': '0.1.0',
	'license': 'OpenRAIL-AS',
	'author': 'Abraham Paul Jaison',
	'url': 'https://github.com/AbrahamPaulJ/pocketfuse'
}


def get(key : str) -> Optional[str]:
	return METADATA.get(key)
