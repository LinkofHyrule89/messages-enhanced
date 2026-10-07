# VPN self-healing (GCP VM)

The app user's (uid 990) traffic goes through WireGuard `wg0` via policy
rules (priority 99/100, table 51820) with an nftables kill switch.

- `50-keep-vpn-rules.conf` → `/etc/systemd/networkd.conf.d/`: stops
  systemd-networkd from flushing those rules on reconfigure (root cause of
  the 2026-10-05 outage).
- `me-vpn-watchdog.sh` → `/usr/local/sbin/me-vpn-watchdog`, with
  `me-vpn-watchdog.{service,timer}` → `/etc/systemd/system/`
  (`systemctl enable --now me-vpn-watchdog.timer`). Logs: `journalctl -t me-vpn-watchdog`.
- Make `PreDown` in `/etc/wireguard/wg0.conf` tolerant (`... || true`) so a
  stop never aborts and leaves `wg0` behind.
