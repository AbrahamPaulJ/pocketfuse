from typing import Optional

from pocketfuse.locales import LOCALES

CURRENT_LANGUAGE = 'en'


def get(key : str, **values : object) -> Optional[str]:
	message = LOCALES.get(CURRENT_LANGUAGE).get(key)

	if message and values:
		return message.format(**values)
	return message
