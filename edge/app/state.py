"""App-wide command lifecycle state (one registry per app instance).

Duplicates are keyed on message_id: a repeated ID returns the existing
state and is never re-executed. Shared across connections so a retry after
a reconnect is still suppressed; bounded so a peer cannot grow it forever.
"""

from __future__ import annotations

from collections import OrderedDict

# Far above any realistic retry window; with protocol.MAX_MESSAGE_ID_LEN
# this caps retained ID text at 512 KiB.
MAX_TRACKED_IDS = 4096


class CommandRegistry:
    """Maps message_id -> lifecycle status string (LRU-bounded)."""

    def __init__(self, max_entries: int = MAX_TRACKED_IDS) -> None:
        self.max_entries = max_entries
        self._statuses: OrderedDict[str, str] = OrderedDict()

    def register(self, message_id: str) -> str:
        """Record a new command ID. Returns "NEW" or "DUP" if already seen.

        Evicts the least recently registered/retried ID once over the cap.
        """
        if message_id in self._statuses:
            self._statuses.move_to_end(message_id)
            return "DUP"
        self._statuses[message_id] = "RECEIVED"
        while len(self._statuses) > self.max_entries:
            self._statuses.popitem(last=False)
        return "NEW"

    def get_status(self, message_id: str) -> str | None:
        """Return the stored status, or None for an unknown ID."""
        return self._statuses.get(message_id)

    def set_status(self, message_id: str, status: str) -> None:
        """Advance (or force) the stored status for a command ID."""
        self._statuses[message_id] = status
