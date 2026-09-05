// The local SeekFlux stack does not provision Cloudflare bindings. Keep the
// module import resolvable under Vinext's Node dev runtime; binding consumers
// still fail explicitly when a missing binding is actually requested.
export const env: Record<string, never> = {};
