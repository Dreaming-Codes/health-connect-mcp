import type { CodeTicket, Gate, OwnerHub } from "./access";
import type { PhoneHub } from "./phone-hub";

export interface Env {
  PHONE: DurableObjectNamespace<PhoneHub>;
  OWNER: DurableObjectNamespace<OwnerHub>;
  TICKET: DurableObjectNamespace<CodeTicket>;
  GATE: DurableObjectNamespace<Gate>;
  DEVICE_TOKEN_HASH: string;
  MCP_TOKEN: string;
  PUSH_TIMEOUT_MS?: string;
  FCM_SERVICE_ACCOUNT?: string;
  FCM_ENDPOINT?: string;
}

export const phoneHub = (env: Env, deviceId: string) => env.PHONE.get(env.PHONE.idFromName(deviceId));
export const ownerHub = (env: Env, ownerId: string) => env.OWNER.get(env.OWNER.idFromName(ownerId));
export const ticket = (env: Env, code: string) => env.TICKET.get(env.TICKET.idFromName(code));
