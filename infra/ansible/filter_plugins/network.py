"""Address-plan helpers, so the playbooks need no extra collection or Python package for them."""
import ipaddress

from ansible.errors import AnsibleFilterError


def _parse_range(text):
    try:
        start, end = (ipaddress.IPv4Address(part.strip()) for part in str(text).split("-"))
    except ValueError as error:
        raise AnsibleFilterError(f"'{text}' is not an IPv4 range like 10.0.0.10-10.0.0.20: {error}")
    if start > end:
        raise AnsibleFilterError(f"'{text}' starts after it ends")
    return start, end


def ipv4_range_without(range_text, address):
    """Split 'start-end' around one address: the ranges that remain, possibly none.

    '10.10.0.200-10.10.0.230' | ipv4_range_without('10.10.0.200') -> ['10.10.0.201-10.10.0.230']
    """
    start, end = _parse_range(range_text)
    try:
        excluded = ipaddress.IPv4Address(str(address).strip())
    except ValueError as error:
        raise AnsibleFilterError(f"'{address}' is not an IPv4 address: {error}")
    if not start <= excluded <= end:
        raise AnsibleFilterError(f"{excluded} is not inside {range_text}")
    remaining = []
    if start < excluded:
        remaining.append(f"{start}-{excluded - 1}")
    if excluded < end:
        remaining.append(f"{excluded + 1}-{end}")
    return remaining


class FilterModule:
    def filters(self):
        return {"ipv4_range_without": ipv4_range_without}
