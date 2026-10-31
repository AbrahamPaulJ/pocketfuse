import ipaddress
import socket
from typing import List, Optional, Set

from pocketfuse.types import Address, AddressKind, Host

TAILNET_NETWORK = ipaddress.ip_network('100.64.0.0/10')


def classify_address(host : Host) -> AddressKind:
	address = ipaddress.ip_address(host)

	if address.is_loopback:
		return 'loopback'
	if address in TAILNET_NETWORK:
		return 'tailnet'
	if address.is_private or address.is_link_local:
		return 'lan'
	return 'public'


def detect_addresses() -> List[Address]:
	addresses : List[Address] = []
	hosts : Set[Host] = set()

	try:
		for address_info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
			hosts.add(str(address_info[4][0]))
	except socket.gaierror:
		pass

	route_host = detect_route_host()

	for host in sorted(hosts, key = lambda host : (host != route_host, host)):
		addresses.append(
		{
			'host': host,
			'kind': classify_address(host)
		})

	return addresses


def detect_route_host() -> Optional[Host]:
	try:
		with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as route_socket:
			route_socket.connect(('10.255.255.255', 1))
			return str(route_socket.getsockname()[0])
	except OSError:
		return None


def suggest_host(addresses : List[Address]) -> Optional[Host]:
	for kind in [ 'tailnet', 'lan' ]:
		for address in addresses:
			if address.get('kind') == kind:
				return address.get('host')

	return None


def is_public_host(host : Host) -> bool:
	try:
		return classify_address(host) == 'public' and host != '0.0.0.0'
	except ValueError:
		return False
