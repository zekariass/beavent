import { execSync } from "node:child_process";
import { api, type SeededSalon } from "./seed.ts";
import { codeFor, newPhone } from "./support.ts";

/**
 * Gets a seeded business onto the marketplace the way a real one would: its owner asks, a platform reviewer lists
 * it. The reviewer is a new account that is given the OPS_ADMIN role in the database (needs `psql`, see
 * db/scripts/grant-staff-role.sh; the PG* variables point at the database). Search shows it at once.
 */
export async function listOnMarketplace(salon: SeededSalon): Promise<void> {
  await api("PUT", `/v1/orgs/${salon.orgId}/marketplace`, { requested: true }, salon.token);

  const phone = newPhone();
  const first = await api("POST", "/v1/auth/otp/request", {
    phone,
    purpose: "SIGN_IN",
    clientApp: "MOBILE_APP",
    channel: "SMS",
  });
  await api("POST", "/v1/auth/otp/verify", {
    challengeId: first.challengeId,
    code: await codeFor(phone),
  });
  execSync(`db/scripts/grant-staff-role.sh ${phone} OPS_ADMIN ET`, {
    cwd: "../..",
    stdio: "ignore",
    env: {
      ...process.env,
      PGHOST: process.env.PGHOST ?? "localhost",
      PGPORT: process.env.PGPORT ?? "5433",
      PGUSER: process.env.PGUSER ?? "beavent",
      PGDATABASE: process.env.PGDATABASE ?? "beavent",
      PGPASSWORD: process.env.PGPASSWORD ?? "beavent",
    },
  });
  // A new code can be sent 30 seconds after the last one.
  await new Promise((resolve) => setTimeout(resolve, 31_000));
  const second = await api("POST", "/v1/auth/otp/request", {
    phone,
    purpose: "SIGN_IN",
    clientApp: "ADMIN_CONSOLE",
    channel: "SMS",
  });
  const { accessToken } = await api("POST", "/v1/auth/otp/verify", {
    challengeId: second.challengeId,
    code: await codeFor(phone, 1),
  });
  await api(
    "POST",
    `/v1/admin/orgs/${salon.orgId}/marketplace-decision`,
    { decision: "LIST", reason: "Listed for the end-to-end test." },
    accessToken,
  );
}
