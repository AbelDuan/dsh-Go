// PRoot launches import this through NODE_OPTIONS. There Node resolves names
// through glibc, which sends A and AAAA on one socket: a network that refuses
// AAAA fails both (#152). Use Direct's per-family resolver instead. If it
// cannot start (no usable resolv.conf), keep the native lookup: this must never
// stop a Node process from running.
import net from 'node:net';
import { installLookup } from './dns.mjs';

// Happy-eyeballs abandons one candidate address after 250 ms by default
// (net.getDefaultAutoSelectFamilyAttemptTimeout()). On a route whose RTT to the
// endpoint is above that window every attempt is dropped and the connection
// fails as ETIMEDOUT after roughly two windows, which surfaces as model and
// model-listing requests failing and being retried, even though DNS, TCP and TLS
// all work. Measured on a Wi-Fi whose international RTT to a Cloudflare edge was
// 300-390 ms: 6/15 listings succeeded with the default window, 15/15 with a
// wider one. Keep the fallback behaviour, only widen the window.
try {
  net.setDefaultAutoSelectFamilyAttemptTimeout(1500);
} catch {
  console.error('[dsh-app] autoSelectFamily attempt timeout not adjustable; keeping the default');
}

try {
  installLookup({ resolvConf: '/etc/resolv.conf', hostsFile: '/etc/hosts' });
} catch {
  console.error('[dsh-app] proot DNS initialization failed; native lookup retained');
}
