from typing import Any, Dict, List, Literal, Optional, TypeAlias, TypedDict

Token : TypeAlias = str
ApiKey : TypeAlias = str
Host : TypeAlias = str
Port : TypeAlias = int
Url : TypeAlias = str

AddressKind = Literal['loopback', 'tailnet', 'lan', 'public']
Address = TypedDict('Address',
{
	'host' : Host,
	'kind' : AddressKind
})

Pairing = TypedDict('Pairing',
{
	'host' : Host,
	'port' : Port,
	'api_key' : Optional[ApiKey]
})

ApiSession = TypedDict('ApiSession',
{
	'access_token' : Token,
	'refresh_token' : Token
})

ProbeStatus = Literal['passed', 'failed', 'skipped']
ProbeResult = TypedDict('ProbeResult',
{
	'name' : str,
	'status' : ProbeStatus,
	'duration' : float,
	'detail' : str
})
ProbeReport : TypeAlias = List[ProbeResult]

JsonBody : TypeAlias = Dict[str, Any]
Locales : TypeAlias = Dict[str, Dict[str, str]]
