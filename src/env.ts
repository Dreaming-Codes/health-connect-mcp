import type { CodeTicket, Gate } from "./access";
import type { PhoneHub } from "./phone-hub";

export interface Env {
  PHONE: DurableObjectNamespace<PhoneHub>;
  TICKET: DurableObjectNamespace<CodeTicket>;
  GATE: DurableObjectNamespace<Gate>;
  PUSH_TIMEOUT_MS?: string;
  FCM_SERVICE_ACCOUNT?: string;
  FCM_ENDPOINT?: string;
}

export const phoneHub = (env: Env, deviceId: string) => env.PHONE.get(env.PHONE.idFromName(deviceId));
export const ticket = (env: Env, code: string) => env.TICKET.get(env.TICKET.idFromName(code));
