export interface IceServerConfig {
  urls: string;
}

const P2P_STUN_SERVERS: IceServerConfig[] = [
  { urls: "stun:piko-ipv6.juren233.top:3478" },
  { urls: "stun:stun.l.google.com:19302" },
  { urls: "stun:stun.cloudflare.com:3478" },
];

export function p2pIceServers(): IceServerConfig[] {
  return P2P_STUN_SERVERS.map((server) => ({ ...server }));
}
