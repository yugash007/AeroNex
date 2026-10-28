"""App-wide command lifecycle state (one registry per app instance).

Duplicates are keyed on (owner, message_id): a repeated ID from the same
client returns the existing state and is never re-executed. Shared across
connections so a retry after a reconnect is still suppressed. Each owner (a
client peer) has its own bounded namespace, so one client can neither evict
another client's IDs (which would let a cancelled command run again on
retry) nor cancel another client's command.
"""

from __future__ import annotations

from collections import OrderedDict

# Far above any realistic retry window; with protocol.MAX_MESSAGE_ID_LEN
# this caps retained ID text at 512 KiB per owner.
MAX_TRACKED_IDS = 4096
# Owners remembered at once; the least recently active is forgotten first.
MAX_OWNERS = 64
DEFAULT_OWNER = "local"


class CommandRegistry:
    """Maps (owner, message_id) -> lifecycle status string (LRU-bounded per owner)."""

    def __init__(self, max_entries: int = MAX_TRACKED_IDS, max_owners: int = MAX_OWNERS) -> None:
        self.max_entries = max_entries
        self.max_owners = max_owners
        self._owners: OrderedDict[str, OrderedDict[str, str]] = OrderedDict()

    def _ids(self, owner: str, create: bool) -> OrderedDict[str, str] | None:
        ids = self._owners.get(owner)
        if ids is None and create:
            ids = self._owners[owner] = OrderedDict()
            while len(self._owners) > self.max_owners:
                self._owners.popitem(last=False)
        if ids is not None:
            self._owners.move_to_end(owner)
        return ids

    def register(self, message_id: str, owner: str = DEFAULT_OWNER) -> str:
        """Record a new command ID. Returns "NEW" or "DUP" if already seen.

        Evicts the owner's least recently used ID once over the cap.
        """
        ids = self._ids(owner, create=True)
        assert ids is not None
        if message_id in ids:
            ids.move_to_end(message_id)
            return "DUP"
        ids[message_id] = "RECEIVED"
        while len(ids) > self.max_entries:
            ids.popitem(last=False)
        return "NEW"

    def get_status(self, message_id: str, owner: str = DEFAULT_OWNER) -> str | None:
        """Return the stored status, or None for an ID this owner never sent."""
        ids = self._owners.get(owner)
        return None if ids is None else ids.get(message_id)

    def set_status(self, message_id: str, status: str, owner: str = DEFAULT_OWNER) -> None:
        """Advance (or force) the stored status for a command ID; it becomes the most recently used."""
        ids = self._ids(owner, create=True)
        assert ids is not None
        ids[message_id] = status
        ids.move_to_end(message_id)
