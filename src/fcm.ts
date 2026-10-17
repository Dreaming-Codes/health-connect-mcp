export interface ServiceAccount { project_id: string; client_email: string; private_key: string; token_uri?: string }

export const b64url = (b: ArrayBuffer | Uint8Array | string) => {
  const bytes = typeof b === "string" ? new TextEncoder().encode(b) : new Uint8Array(b as ArrayBuffer);
  let s = ""; for (const x of bytes) s += String.fromCharCode(x);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
};

async function importKey(pem: string): Promise<CryptoKey> {
  const der = Uint8Array.from(atob(pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "")), (c) => c.charCodeAt(0));
  return crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
}

export async function mintAccessToken(sa: ServiceAccount): Promise<{ token: string; expiresAt: number }> {
  const now = Math.floor(Date.now() / 1000);
  const aud = sa.token_uri ?? "https://oauth2.googleapis.com/token";
  const head = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = b64url(JSON.stringify({ iss: sa.client_email, scope: "https://www.googleapis.com/auth/firebase.messaging", aud, iat: now, exp: now + 3600 }));
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", await importKey(sa.private_key), new TextEncoder().encode(`${head}.${claims}`));
  const res = await fetch(aud, {
    method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: `${head}.${claims}.${b64url(sig)}` }),
  });
  const j: any = await res.json();
  if (!res.ok || !j.access_token) throw new Error(`OAuth token mint failed (${res.status}): ${j.error ?? ""} ${j.error_description ?? ""}`.trim());
  return { token: j.access_token, expiresAt: Date.now() + (j.expires_in ?? 3600) * 1000 };
}

export class FcmError extends Error { constructor(msg: string, public status: number, public code?: string) { super(msg); } }

export const FCM_ENDPOINT = "https://fcm.googleapis.com";

export async function sendData(sa: ServiceAccount, accessToken: string, fcmToken: string, data: Record<string, string>, ttlSec: number, endpoint = FCM_ENDPOINT) {
  const res = await fetch(`${endpoint}/v1/projects/${sa.project_id}/messages:send`, {
    method: "POST", headers: { authorization: `Bearer ${accessToken}`, "content-type": "application/json" },
    body: JSON.stringify({ message: { token: fcmToken, data, android: { priority: "high", ttl: `${ttlSec}s` } } }),
  });
  const j: any = await res.json().catch(() => ({}));
  if (!res.ok) {
    const code = j?.error?.details?.find((d: any) => d.errorCode)?.errorCode ?? j?.error?.status;
    throw new FcmError(`FCM send failed (${res.status} ${code ?? ""}): ${j?.error?.message ?? ""}`.trim(), res.status, code);
  }
  return j.name as string;
}
