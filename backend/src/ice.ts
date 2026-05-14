export interface IceServerConfig {
  urls: string;
}

const CLOUDFLARE_STUN_SERVERS: IceServerConfig[] = [
  { urls: "stun:stun.cloudflare.com:3478" },
  { urls: "stun:stun.cloudflare.com:53" },
];

export function p2pIceServers(): IceServerConfig[] {
  return CLOUDFLARE_STUN_SERVERS.map((server) => ({ ...server }));
}
