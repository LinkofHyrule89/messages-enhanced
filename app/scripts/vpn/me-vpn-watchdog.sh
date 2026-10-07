#!/bin/bash
# Messages Enhanced VPN watchdog (installed as /usr/local/sbin/me-vpn-watchdog,
# run every minute by me-vpn-watchdog.timer).
#
# The app user's traffic is policy-routed into a WireGuard tunnel (wg0) and a
# kill switch drops anything else. This keeps that path working:
#   - routing rules missing (e.g. systemd-networkd flushed "foreign" rules
#     during a DHCP/netplan reconfigure): re-add them, no restart needed
#   - wg0 missing / wg-quick failed / handshake older than 180 s (WireGuard
#     rejects keys after 180 s, so the tunnel is dead): restart the tunnel
#   - kill switch table missing: restart nftables
#   - app enabled but not running (it stops with the tunnel): start it
set -u
UID_APP=990 TABLE=51820 MARK=51820 STALE=${ME_WG_STALE_SECS:-180} APP=messages-enhanced WG=wg-quick@wg0

log() { logger -t me-vpn-watchdog -- "$*"; if [ -t 1 ]; then echo "$*"; fi; return 0; }

restart_wg() {
	log "restarting wg0: $1"
	systemctl stop "$WG" 2>/dev/null
	ip link show wg0 >/dev/null 2>&1 && ip link del wg0
	for f in -4 -6; do
		ip "$f" rule del priority 99 2>/dev/null
		ip "$f" rule del priority 100 2>/dev/null
	done
	systemctl reset-failed "$WG" 2>/dev/null
	systemctl start "$WG" || log "wg-quick failed to start"
}

if ! ip link show wg0 >/dev/null 2>&1 || ! systemctl is-active -q "$WG"; then
	restart_wg "wg0 missing or $WG not active"
else
	fixed=""
	for f in -4 -6; do
		ip "$f" rule show priority 99 | grep -q "fwmark" ||
			{ ip "$f" rule add fwmark $MARK lookup main priority 99 && fixed="$fixed fwmark$f"; }
		ip "$f" rule show priority 100 | grep -q "uidrange $UID_APP-$UID_APP lookup $TABLE" ||
			{ ip "$f" rule add uidrange $UID_APP-$UID_APP lookup $TABLE priority 100 && fixed="$fixed uid$f"; }
		ip "$f" route show table $TABLE | grep -q "default dev wg0" ||
			{ ip "$f" route replace default dev wg0 table $TABLE && fixed="$fixed route$f"; }
	done
	[ -n "$fixed" ] && log "re-added missing VPN routing:$fixed"

	hs=$(wg show wg0 latest-handshakes 2>/dev/null | awk '{print $2; exit}')
	age=$(($(date +%s) - ${hs:-0}))
	if [ "${hs:-0}" -eq 0 ] || [ "$age" -gt "$STALE" ]; then
		# Brand-new interface: give it one cycle to complete a first handshake.
		up=$(( $(date +%s) - $(stat -c %Y /sys/class/net/wg0 2>/dev/null || date +%s) ))
		if [ "${hs:-0}" -ne 0 ] || [ "$up" -gt 90 ]; then
			restart_wg "handshake stale (${age}s)"
		fi
	fi
fi

nft list table inet me_killswitch >/dev/null 2>&1 || { log "kill switch table missing; restarting nftables"; systemctl restart nftables; }

state=$(systemctl show -p ActiveState --value "$APP")
if systemctl is-enabled -q "$APP" && { [ "$state" = inactive ] || [ "$state" = failed ]; }; then
	systemctl reset-failed "$APP" 2>/dev/null
	systemctl start "$APP" && log "started $APP" || log "$APP failed to start"
fi
exit 0
